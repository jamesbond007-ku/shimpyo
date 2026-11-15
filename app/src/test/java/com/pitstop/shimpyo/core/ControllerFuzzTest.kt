package com.pitstop.shimpyo.core

import com.pitstop.shimpyo.core.sim.SimNaviGateway
import com.pitstop.shimpyo.core.sim.SimPreDriveSource
import com.pitstop.shimpyo.core.sim.SimVehicleGateway
import kotlin.random.Random

private class FzClk(var now: Long = 1, var hour: Int = 3) : Clock { override fun nowMs() = now; override fun hourOfDay() = hour }
private class FzSp(val rnd: Random) : SpeechGateway {
    override val backend = Backend.SIM
    override var canListen = true
    var pendingSpeak: (() -> Unit)? = null
    var pendingListen: ((String, Long?) -> Unit)? = null
    override fun start() = Unit
    override fun stop() = Unit
    override fun speak(text: String, onDone: () -> Unit) { if (rnd.nextInt(4) == 0) pendingSpeak = onDone else onDone() }
    override fun listen(timeoutMs: Long, onResult: (String, Long?) -> Unit) {
        if (rnd.nextInt(3) == 0) { pendingListen = onResult; return }
        val ans = listOf("응", "아니", "", "쉬고 싶어", "일곱 시간", "아침 7시", "9", "3", "맞아", "56", "같이 할게", "혼자 할게", "뭐라고?", "그만", "괜찮아 쉬었다 가자")
        val a = ans[rnd.nextInt(ans.size)]
        onResult(a, if (a.isEmpty()) null else 800L + rnd.nextInt(3000))
    }
    override fun cancel() { pendingSpeak = null; pendingListen = null }
    fun flush() { pendingSpeak?.let { pendingSpeak = null; it() }; pendingListen?.let { pendingListen = null; it("응", 1500L) } }
}

class ControllerFuzzTest {
    /**
     * 무작위 상황(주차·출발·도착·버튼·무응답·되묻기·늦은 음성 콜백·동승자·워치·캘린더·배속)을 섞어 돌리며
     * 안전 불변식을 검사한다: 주행 중 등받이 눕힘·창문 열림 3초 초과 금지, 휴식 대기·깨우기·대화 단계에 갇힘 금지,
     * 운행 종료 뒤 차량 상태 원복, 예외 없음. (점검 때 이 시험으로 깨우기 중 출발·안내 말 끊김 문제를 찾았다.)
     */
    @org.junit.Test
    fun randomScenariosKeepSafetyInvariants() {
    val csv = listOf("src/main/assets/shelters.csv", "app/src/main/assets/shelters.csv").map { java.io.File(it) }.first { it.exists() }.readText()
    var problems = 0
    // 기본 묶음은 600개. 심층 점검은 환경 변수로 같은 시험을 더 많은 재현 가능한 시드에 적용한다.
    val count = System.getenv("SHIMPYO_FUZZ_SEEDS")?.toIntOrNull()?.coerceIn(600, 100_000) ?: 600
    val start = System.getenv("SHIMPYO_FUZZ_START")?.toIntOrNull()?.coerceIn(0, 1_000_000) ?: 0
    for (seed in start until start + count) {
        val rnd = Random(seed)
        val clock = FzClk(hour = rnd.nextInt(24))
        val sp = FzSp(rnd); sp.canListen = rnd.nextInt(5) != 0
        val vehicle = SimVehicleGateway(seatSupported = rnd.nextBoolean())
        val navi = SimNaviGateway()
        var ui = UiState()
        val pre = SimPreDriveSource()
        if (rnd.nextBoolean()) pre.sleep = SleepRecord(sleepHours = listOf(3.5, 5.5, 7.5).random(rnd), wakeHour = rnd.nextInt(24).toDouble(), sourceLabel = "워치")
        if (rnd.nextBoolean()) pre.plan = PlannedDrive(title = "출장", expectedMinutes = listOf(60, 180).random(rnd), night = rnd.nextBoolean())
        val c = ShimpyoController(vehicle, navi, sp, clock, ShimpyoConfig(timeScale = listOf(1, 60).random(rnd), autoStartOnDrive = rnd.nextBoolean()), rnd,
            preDrive = pre, initialTeamPreference = TeamPreference.entries.random(rnd)) { ui = it }
        try {
            c.attach(); c.setShelters(ShelterDirectory.parseCsv(csv))
            var lat = 36.70; val lon = 127.4567
            navi.simulateLocation(GeoPoint(lat, lon))
            if (rnd.nextBoolean()) c.startTrip()
            var stuck = 0; var lastPhase = ui.phase; var reclinedDriving = 0; var windowDriving = 0; var talkStuck = 0
            for (step in 0 until 400) {
                clock.now += 1000
                lat += 0.002; navi.simulateLocation(GeoPoint(lat, lon + rnd.nextDouble(-0.0002, 0.0002)))
                when (rnd.nextInt(60)) {
                    0 -> vehicle.setParked(true); 1 -> vehicle.setParked(false); 2 -> navi.simulateArrival()
                    3 -> c.requestRestNow(); 4 -> c.cancelRest(); 5 -> c.injectCorrectAnswer(); 6 -> vehicle.setPassenger(rnd.nextBoolean())
                    7 -> c.passengerTouchAnswer("O"); 8 -> sp.flush(); 9 -> if (rnd.nextInt(10) == 0) c.endTrip(); 10 -> c.startTrip()
                    11 -> c.demoStartGame(ShimpyoController.DemoGame.entries.random(rnd))
                }
                c.tick()
                // 멈춤 감시: 주차 상태로 휴식 대기 단계에 오래 머물거나, 깨우기 단계가 끝나지 않으면 문제.
                val s = c.snapshot()
                val shouldMove = (s.phase == Phase.WAITING_PARK && s.parked == true) || s.phase == Phase.WAKING
                if (shouldMove && s.phase == lastPhase && sp.pendingSpeak == null && sp.pendingListen == null) stuck++ else stuck = 0
                lastPhase = s.phase
                if (stuck > 60 && sp.pendingSpeak == null && sp.pendingListen == null) {
                    problems++; println("STUCK seed=$seed phase=${s.phase} parked=${s.parked}"); if (seed == 58) s.logs.takeLast(30).forEach { println("   " + it.text) }; break
                }
                // 안전 불변식: 주행 중(P단 아님)에 등받이가 눕혀진 채로 3초 넘게 있으면 안 된다(어느 단계든).
                if (vehicle.cabin.seatReclined && s.parked == false) reclinedDriving++ else reclinedDriving = 0
                if (vehicle.cabin.windowOpen && s.parked == false) windowDriving++ else windowDriving = 0
                if (windowDriving > 3) { problems++; println("WINDOW-OPEN-DRIVING seed=$seed phase=${s.phase} log=${s.logs.takeLast(5).map { it.text }}"); break }
                if ((s.phase == Phase.TALKING || s.phase == Phase.OFFERING) && sp.pendingSpeak == null && sp.pendingListen == null) talkStuck++ else talkStuck = 0
                if (talkStuck > 90) { problems++; println("TALK-STUCK seed=$seed phase=${s.phase} log=${s.logs.takeLast(6).map { it.text }}"); break }
                if (reclinedDriving > 3) { problems++; println("RECLINED-WHILE-DRIVING seed=$seed phase=${s.phase} log=${s.logs.takeLast(5).map { it.text }}"); break }
            }
            // 운행 종료 뒤 차량 상태가 원래대로 돌아와야 한다(시트는 주행 중에도 세운다).
            c.endTrip(); sp.flush(); repeat(3) { clock.now += 1000; c.tick(); sp.flush() }
            val cab = vehicle.cabin
            if (cab.seatReclined || cab.cabinDim || cab.restClimate || cab.freshAirBoost || cab.windowOpen || cab.prepCelsius != null) {
                problems++; println("NOT-RESTORED seed=$seed $cab log=${c.snapshot().logs.takeLast(6).map { it.text }}")
            }
        } catch (t: Throwable) { problems++; println("EXCEPTION seed=$seed $t ${t.stackTrace.take(3)}") }
        if (problems > 15) break
    }
    org.junit.Assert.assertEquals(0, problems)
    }
}
