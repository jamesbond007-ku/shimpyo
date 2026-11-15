package com.pitstop.shimpyo.core

import com.pitstop.shimpyo.core.sim.SimNaviGateway
import com.pitstop.shimpyo.core.sim.SimVehicleGateway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 0.2.1 점검에서 고친 동작: 마이크 없는 제안, 자동 시작, 노선 우선, 차량 주행 시간, 대답 종류별 기준, 가짜 ‘완료’. */
class ReviewFixesTest {

    private class FakeClock(var now: Long = 0, var hour: Int = 23) : Clock {
        override fun nowMs() = now
        override fun hourOfDay() = hour
    }

    private class Speech : SpeechGateway {
        override val backend = Backend.SIM
        override var canListen = true
        val spoken = mutableListOf<String>()
        var answer: () -> Pair<String, Long?> = { "응" to 1000L }
        override fun start() = Unit
        override fun stop() = Unit
        override fun speak(text: String, onDone: () -> Unit) { spoken += text; onDone() }
        override fun listen(timeoutMs: Long, onResult: (String, Long?) -> Unit) { val (t, l) = answer(); onResult(t, l) }
        override fun cancel() = Unit
    }

    private val clock = FakeClock()
    private val speech = Speech()
    private val vehicle = SimVehicleGateway()
    private val navi = SimNaviGateway()
    private var ui = UiState()

    private fun controller(config: ShimpyoConfig = ShimpyoConfig(timeScale = 60)) =
        ShimpyoController(vehicle, navi, speech, clock, config, Random(7)) { ui = it }

    @Test
    fun offerWithoutMicrophoneIsNotADecline() {
        val c = controller()
        c.attach()
        c.startTrip()
        speech.canListen = false
        speech.answer = { "" to null }
        // 수면 점검 없이 위험만 올린다: 새벽 3시 + 4시간 수면 기록이 없으니 주행 시간으로.
        clock.hour = 3
        var guard = 0
        while (speech.spoken.none { it.contains("지금은 대답을 들을 수 없어요") } && guard++ < 300) { clock.now += 1000; c.tick() }
        assertTrue(speech.spoken.any { it.contains("지금은 대답을 들을 수 없어요") })
        assertEquals("거절로 세지 않는다", 0, ui.declinedOffers)
        assertFalse(speech.spoken.any { it.startsWith("알겠어요. 대신 대화를") })
    }

    @Test
    fun tripStartsByItselfWhenTheCarLeavesPark() {
        vehicle.setParked(true)
        val c = controller(ShimpyoConfig(autoStartOnDrive = true))
        c.attach()
        assertEquals(Phase.IDLE, ui.phase)
        vehicle.setParked(false)
        assertEquals(Phase.MONITORING, ui.phase)
    }

    @Test
    fun noAutoStartInDemoConfig() {
        vehicle.setParked(true)
        val c = controller(ShimpyoConfig(autoStartOnDrive = false))
        c.attach()
        vehicle.setParked(false)
        assertEquals(Phase.IDLE, ui.phase)
    }

    @Test
    fun prefersShelterOnTheRoadWeAreOn() {
        val dir = ShelterDirectory(
            listOf(
                Shelter("옆고속도로", GeoPoint(37.05, 127.01), road = "평택제천선"),
                Shelter("우리노선", GeoPoint(37.10, 127.00), road = "중부선"),
            ),
        )
        val here = GeoPoint(37.0, 127.0)
        assertEquals("노선을 모르면 가장 가까운 곳", "옆고속도로", dir.nearestAhead(here, 0.0)!!.shelter.name)
        assertEquals("달리는 노선을 알면 그 노선의 쉼터", "우리노선", dir.nearestAhead(here, 0.0, preferRoad = "중부선")!!.shelter.name)
        assertEquals("그 노선에 앞쪽 쉼터가 없으면 전체에서", "옆고속도로", dir.nearestAhead(here, 0.0, preferRoad = "경부선")!!.shelter.name)
    }

    @Test
    fun offerSaysStraightLineDistance() {
        val c = controller()
        c.attach()
        c.setShelters(ShelterDirectory.parseCsv("졸음쉼터명,위도,경도\n앞쪽졸음쉼터,37.08,127.00"))
        navi.simulateLocation(GeoPoint(36.99, 127.0))
        navi.simulateLocation(GeoPoint(37.00, 127.0))
        c.startTrip()
        clock.hour = 3
        speech.answer = { "응" to 2600L }
        var guard = 0
        while (ui.phase != Phase.EN_ROUTE && guard++ < 300) { clock.now += 1000; c.tick() }
        assertTrue(speech.spoken.any { it.contains("직선으로 약 8킬로미터 앞에 앞쪽졸음쉼터가 있어요") })
    }

    @Test
    fun vehicleTripTimeDetectsSecondsAndIgnitionReset() {
        val minutes = VehicleTripTime()
        minutes.record(30, 0)
        minutes.record(34, 4 * 60_000L)
        assertEquals(34, minutes.minutes())

        val seconds = VehicleTripTime()
        seconds.record(600, 0)
        assertNull("단위를 확인하기 전에는 쓰지 않는다", seconds.minutes())
        seconds.record(840, 4 * 60_000L)
        assertTrue(seconds.unitSeconds)
        assertEquals(14, seconds.minutes())

        val big = VehicleTripTime()
        big.record(5_000, 0)
        assertNull("판정 전 12시간 넘는 값은 믿지 않는다", big.minutes())

        val reset = VehicleTripTime()
        reset.record(50, 0)
        reset.record(52, 4 * 60_000L)
        reset.record(1, 5 * 60_000L) // 시동을 껐다 켬
        assertEquals("잠깐 시동을 끈 것으로 연속 주행이 0이 되지 않는다", 53, reset.minutes())
    }

    @Test
    fun thinkingQuestionsUseTheirOwnBaseline() {
        val b = LatencyBaseline()
        b.record(1000, AnswerKind.QUICK); b.record(1000, AnswerKind.QUICK)
        // 계산 문제는 원래 느리다. 처음 두 개는 계산 문제의 기준을 잡는 데만 쓰고 비교하지 않는다.
        b.record(2500, AnswerKind.THINK); b.record(2500, AnswerKind.THINK)
        assertNull("기준을 잡는 동안은 ‘느려짐’으로 보지 않는다", b.ratio())
        b.record(2500, AnswerKind.THINK); b.record(2500, AnswerKind.THINK); b.record(2500, AnswerKind.THINK)
        assertEquals(1.0, b.ratio()!!, 1e-9)
        // 같은 종류에서 두 배로 느려지면 잡힌다.
        b.record(5000, AnswerKind.THINK); b.record(5000, AnswerKind.THINK)
        assertEquals(2.0, b.ratio()!!, 1e-9)
    }

    @Test
    fun liveFallbackVehicleDoesNotPretend() {
        val fake = SimVehicleGateway(controlsWork = false)
        var result: ControlResult? = null
        fake.setParked(true)
        fake.setDriverSeatRecline(true) { result = it }
        assertEquals(ControlResult.UNSUPPORTED, result)
        assertFalse(fake.cabin.seatReclined)
    }

    @Test
    fun departLineDoesNotPromiseADestination() {
        assertFalse(NapCapsule.DEPART_LINE.contains("원래 목적지로 안내할게요"))
    }
}

class SecondReviewControllerTest {
    private class FakeClock(var now: Long = 0, var hour: Int = 23) : Clock {
        override fun nowMs() = now
        override fun hourOfDay() = hour
    }
    private class Speech : SpeechGateway {
        override val backend = Backend.SIM
        override var canListen = true
        val spoken = mutableListOf<String>()
        var answer: (String) -> Pair<String, Long?> = { "응" to 1000L }
        private var last = ""
        override fun start() = Unit
        override fun stop() = Unit
        override fun speak(text: String, onDone: () -> Unit) { spoken += text; last = text; onDone() }
        override fun listen(timeoutMs: Long, onResult: (String, Long?) -> Unit) { val (t, l) = answer(last); onResult(t, l) }
        override fun cancel() = Unit
    }

    @Test
    fun sayingRestDuringAGameAddsTheShelterRightAway() {
        val clock = FakeClock(); val speech = Speech(); val vehicle = SimVehicleGateway(); val navi = SimNaviGateway()
        var ui = UiState()
        val c = ShimpyoController(vehicle, navi, speech, clock, ShimpyoConfig(timeScale = 60), Random(3)) { ui = it }
        c.attach()
        c.setShelters(ShelterDirectory.parseCsv("졸음쉼터명,위도,경도\n앞쪽졸음쉼터,37.08,127.00"))
        navi.simulateLocation(GeoPoint(36.99, 127.0)); navi.simulateLocation(GeoPoint(37.00, 127.0))
        c.startTrip()
        // 구구단 문제에 “그만하고 쉬자”(가짜 음성은 바로 대답하므로 놀이를 시작하기 전에 대본을 정한다)
        speech.answer = { q -> if (q.contains("곱하기")) "그만하고 쉬자" to 1200L else "응" to 1000L }
        c.demoStartGame(ShimpyoController.DemoGame.GUGUDAN)
        assertEquals(Phase.EN_ROUTE, ui.phase)
        assertEquals("앞쪽졸음쉼터", navi.restStop?.name)
        assertFalse("다시 묻지 않는다", speech.spoken.any { it.contains("쉬었다 갈까요") })
    }

    @Test
    fun noLongerPromisesAlwaysListening() {
        assertFalse(com.pitstop.shimpyo.core.talk.KssCheckGame.REST_HINT.contains("언제든"))
    }

    @Test
    fun tripTimeWaitsForMovementBeforeDecidingUnit() {
        val t = VehicleTripTime()
        t.record(100, 0)
        t.record(100, 4 * 60_000L) // 서 있었음: 아직 판정 안 함
        t.record(400, 8 * 60_000L) // 그 뒤 4분에 300 증가 → 초 단위
        assertEquals(400 / 60, t.minutes())
    }
}

class ThirdReviewTest {
    @Test
    fun restHintNeverAsksForTheScreenWhileDriving() {
        val hint = com.pitstop.shimpyo.core.talk.KssCheckGame.REST_HINT
        assertTrue(hint.contains("차를 세운 뒤에는 화면"))
        assertFalse(hint.startsWith("졸리면 화면"))
    }

    @Test
    fun wakeLineOnlyPromisesWhatHappens() {
        assertFalse(NapCapsule.wakeLine(15, seatWillRise = true, windowWillOpen = false).contains("창문"))
        assertTrue(NapCapsule.wakeLine(15, seatWillRise = true, windowWillOpen = true).contains("등받이를 천천히 세우고 창문을"))
        assertFalse(NapCapsule.wakeLine(15, seatWillRise = false, windowWillOpen = false).contains("등받이"))
        assertFalse(NapCapsule.DEPART_LINE.contains("지웠어요"))
    }

    @Test
    fun parkedAwayFromShelterWithoutMicPointsToTheButton() {
        val clock = object : Clock { var now = 0L; override fun nowMs() = now; override fun hourOfDay() = 23 }
        val spoken = mutableListOf<String>()
        var canHear = true
        val speech = object : SpeechGateway {
            override val backend = Backend.SIM
            override val canListen get() = canHear
            override fun start() = Unit
            override fun stop() = Unit
            override fun speak(text: String, onDone: () -> Unit) { spoken += text; onDone() }
            override fun listen(timeoutMs: Long, onResult: (String, Long?) -> Unit) = onResult(if (canHear) "응" else "", if (canHear) 2600L else null)
            override fun cancel() = Unit
        }
        val vehicle = SimVehicleGateway(); val navi = SimNaviGateway()
        var ui = UiState()
        val c = ShimpyoController(vehicle, navi, speech, clock, ShimpyoConfig(timeScale = 60), Random(7)) { ui = it }
        c.attach()
        c.setShelters(ShelterDirectory.parseCsv("졸음쉼터명,위도,경도\n앞쪽졸음쉼터,37.08,127.00"))
        navi.simulateLocation(GeoPoint(36.99, 127.0)); navi.simulateLocation(GeoPoint(37.00, 127.0))
        c.startTrip()
        var guard = 0
        while (ui.phase != Phase.EN_ROUTE && guard++ < 300) { clock.now += 1000; c.tick() }
        assertEquals(Phase.EN_ROUTE, ui.phase)
        canHear = false // 내비가 앞으로 나와 마이크를 못 씀
        vehicle.setParked(true)
        clock.now += 1000; c.tick()
        assertTrue(spoken.any { it.contains("‘지금 쉬기’를 눌러 주세요") })
        assertEquals(Phase.EN_ROUTE, ui.phase)
        assertFalse(vehicle.cabin.seatReclined)
        c.requestRestNow()
        assertEquals(Phase.RESTING, ui.phase)
    }
}


class FourthReviewTest {
    private class C(var now: Long = 0) : Clock { override fun nowMs() = now; override fun hourOfDay() = 10 }
    private class Quiet : SpeechGateway {
        override val backend = Backend.SIM
        override val canListen = true
        override fun start() = Unit
        override fun stop() = Unit
        override fun speak(text: String, onDone: () -> Unit) = onDone()
        override fun listen(timeoutMs: Long, onResult: (String, Long?) -> Unit) = onResult("일곱 시간", 1000L)
        override fun cancel() = Unit
    }

    @Test
    fun decliningWordsBeatRestWords() {
        listOf("쉬고 싶지 않아", "쉬어가기 싫어", "쉴 필요 없어").forEach {
            assertEquals(it, com.pitstop.shimpyo.core.talk.KoreanText.Intent.NO, com.pitstop.shimpyo.core.talk.KoreanText.intentOf(it))
            assertFalse(it, com.pitstop.shimpyo.core.talk.KoreanText.wantsRest(it))
        }
        assertTrue(com.pitstop.shimpyo.core.talk.KoreanText.wantsRest("쉬고 싶어"))
    }

    @Test
    fun elapsedTimeAnswersAreNotClockTimes() {
        val k = com.pitstop.shimpyo.core.talk.KoreanText
        assertEquals(3.0, k.hoursSince(k.parseClock("일어난 지 세 시간 됐어")!!, 15.0), 1e-9)
        assertEquals(2.0, k.hoursSince(k.parseClock("두 시간밖에 안 됐어")!!, 15.0), 1e-9)
    }

    @Test
    fun newTripForgetsLastTripsDirection() {
        val dirFile = listOf("src/main/assets/shelters.csv", "app/src/main/assets/shelters.csv").map { java.io.File(it) }.first { it.exists() }
        val clock = C(); val vehicle = SimVehicleGateway(); val navi = SimNaviGateway(); var ui = UiState()
        val c = ShimpyoController(vehicle, navi, Quiet(), clock, ShimpyoConfig(timeScale = 60), Random(1)) { ui = it }
        c.attach(); c.setShelters(ShelterDirectory.parseCsv(dirFile.readText(Charsets.UTF_8)))
        c.startTrip()
        // 오창 짝 사이를 북쪽으로 지난다 → 앞쪽 쉼터에 반대 차로의 농다리가 없다.
        navi.simulateLocation(GeoPoint(36.7340, 127.4567)); navi.simulateLocation(GeoPoint(36.73492, 127.45646))
        assertFalse("first: ${ui.aheadShelters.map { it.shelter.name }} h=${ui.headingDeg}", ui.aheadShelters.any { it.shelter.name == "농다리" })
        c.endTrip()
        // 돌아오는 길(남쪽으로): 지난 운행의 방향을 기억하고 있으면 맞는 쪽 쉼터(농다리)를 걸러 낸다.
        c.startTrip()
        navi.simulateLocation(GeoPoint(36.90, 127.48)); navi.simulateLocation(GeoPoint(36.89, 127.482))
        assertTrue("second: ${ui.aheadShelters.map { it.shelter.name }} h=${ui.headingDeg}", ui.aheadShelters.any { it.shelter.name == "농다리" })
    }

    @Test
    fun autoStartedTripEndsAfterLongParking() {
        val clock = C(); val vehicle = SimVehicleGateway(); var ui = UiState()
        vehicle.setParked(true)
        val c = ShimpyoController(vehicle, SimNaviGateway(), Quiet(), clock, ShimpyoConfig(autoStartOnDrive = true), Random(1)) { ui = it }
        c.attach()
        vehicle.setParked(false)
        assertEquals(Phase.MONITORING, ui.phase)
        repeat(10) { clock.now += 1000; c.tick() }
        vehicle.setParked(true)
        repeat(21) { clock.now += 60_000; c.tick() }
        assertEquals("목적지에 세워 두면 운행이 끝난다", Phase.IDLE, ui.phase)
        vehicle.setParked(false)
        assertEquals("다시 출발하면 새 운행", Phase.MONITORING, ui.phase)
        assertTrue(ui.drivingMinutes <= 1)
    }
}

class FifthReviewTest {
    private class C(var now: Long = 0) : Clock { override fun nowMs() = now; override fun hourOfDay() = 10 }
    private class Sp(var reply: () -> Pair<String, Long?>) : SpeechGateway {
        override val backend = Backend.SIM
        override val canListen = true
        var onListen: (() -> Unit)? = null
        override fun start() = Unit
        override fun stop() = Unit
        override fun speak(text: String, onDone: () -> Unit) = onDone()
        override fun listen(timeoutMs: Long, onResult: (String, Long?) -> Unit) { onListen?.invoke(); val (t, l) = reply(); onResult(t, l) }
        override fun cancel() = Unit
    }

    @Test
    fun arrivalDuringParkQuestionDoesNotFreeze() {
        val clock = C(); val vehicle = SimVehicleGateway(); val navi = SimNaviGateway(); var ui = UiState()
        val speech = Sp { "응" to 2600L }
        val c = ShimpyoController(vehicle, navi, speech, clock, ShimpyoConfig(timeScale = 60), Random(7)) { ui = it }
        c.attach()
        c.setShelters(ShelterDirectory.parseCsv("졸음쉼터명,위도,경도\n앞쪽졸음쉼터,37.08,127.00"))
        navi.simulateLocation(GeoPoint(36.99, 127.0)); navi.simulateLocation(GeoPoint(37.00, 127.0))
        clock.now = 1; c.startTrip()
        var g = 0
        while (ui.phase != Phase.EN_ROUTE && g++ < 300) { clock.now += 1000; c.tick() }
        assertEquals(Phase.EN_ROUTE, ui.phase)
        // “여기서 쉬어 갈까요?”를 묻는 도중 내비가 도착을 알린다(대답은 ‘아니’).
        speech.onListen = { speech.onListen = null; vehicle.setParked(false); navi.simulateArrival() }
        speech.reply = { "아니" to 1500L }
        vehicle.setParked(true)
        clock.now += 1000; c.tick()
        assertEquals(Phase.WAITING_PARK, ui.phase)
        // 다시 P단을 넣으면 휴식이 시작되어야 한다(예전엔 busy가 남아 멈췄다).
        vehicle.setParked(true)
        repeat(3) { clock.now += 1000; c.tick() }
        assertEquals(Phase.RESTING, ui.phase)
    }

    @Test
    fun vehicleTimeTakesOverWithoutJump() {
        val clock = C(); val vehicle = SimVehicleGateway(); var ui = UiState()
        var listener: VehicleGateway.Listener? = null
        val v = object : VehicleGateway by vehicle {
            override fun start(l: VehicleGateway.Listener) { listener = l; vehicle.start(l) }
        }
        val c = ShimpyoController(v, SimNaviGateway(), Sp { "일곱 시간" to 1000L }, clock, ShimpyoConfig(), Random(1)) { ui = it }
        c.attach()
        clock.now = 1; c.startTrip()
        // 그날 시동 뒤 이미 50분 달렸던 차. 운행 시작 뒤 5분 동안 값이 분 단위로 오른다.
        for (m in 0..5) { listener!!.onTripDrivingMinutes(50 + m); clock.now = 1 + m * 60_000L; c.tick() }
        assertTrue("앱 시계 5분에서 차량 값으로 넘어가도 튀지 않는다: ${ui.drivingMinutes}", ui.drivingMinutes in 4..6)
    }

    @Test
    fun demoSpeedDoesNotTreatShortParkAsLong() {
        val clock = C(); val vehicle = SimVehicleGateway(); var ui = UiState()
        val c = ShimpyoController(vehicle, SimNaviGateway(), Sp { "일곱 시간" to 1000L }, clock, ShimpyoConfig(timeScale = 60), Random(1)) { ui = it }
        c.attach()
        clock.now = 1; c.startTrip()
        repeat(40) { clock.now += 1000; c.tick() } // 배속으로 40분 주행
        vehicle.setParked(true)
        repeat(25) { clock.now += 1000; c.tick() } // 실제 25초 주차(배속으로는 25분)
        assertTrue("짧게 세운 것은 쉰 것이 아니다: ${ui.drivingMinutes}", ui.drivingMinutes >= 40)
    }
}
