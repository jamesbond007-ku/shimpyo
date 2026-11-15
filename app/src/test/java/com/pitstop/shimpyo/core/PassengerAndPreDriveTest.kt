package com.pitstop.shimpyo.core

import com.pitstop.shimpyo.core.sim.SimNaviGateway
import com.pitstop.shimpyo.core.sim.SimPreDriveSource
import com.pitstop.shimpyo.core.sim.SimVehicleGateway
import com.pitstop.shimpyo.core.talk.DistanceGuessGame
import com.pitstop.shimpyo.core.talk.KoreanText
import com.pitstop.shimpyo.core.talk.RegionQuizGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 동승자(조용한 감시·같이하기), 차량 특화 놀이, 웨어러블·캘린더 ‘무입력 출발’. */
class PassengerAndPreDriveTest {

    /**
     * 질문을 보고 대답하는 가짜 음성. [hold]가 참인 질문은 바로 답하지 않고 [pending]에 남겨 둔다
     * (그 사이에 동승자 터치 같은 일을 끼워 넣기 위해).
     */
    private class Speech(
        val reply: (String) -> Pair<String, Long?>,
        val hold: (String) -> Boolean = { false },
    ) : SpeechGateway {
        override val backend = Backend.SIM
        override val canListen = true
        val spoken = mutableListOf<String>()
        val asked = mutableListOf<String>()
        var pending: ((String, Long?) -> Unit)? = null
        override fun start() = Unit
        override fun stop() = Unit
        override fun speak(text: String, onDone: () -> Unit) { spoken += text; onDone() }
        override fun listen(timeoutMs: Long, onResult: (String, Long?) -> Unit) {
            val q = spoken.last()
            asked += q
            if (hold(q)) pending = onResult else reply(q).let { (t, ms) -> onResult(t, ms) }
        }
        override fun cancel() = Unit
    }

    private class Rig(
        hour: Int = 10,
        reply: (String) -> Pair<String, Long?> = { standard(it) },
        hold: (String) -> Boolean = { false },
        val preDrive: SimPreDriveSource = SimPreDriveSource(),
        teamPreference: TeamPreference = TeamPreference.ASK,
        csv: String = "졸음쉼터명,위도,경도\n앞쪽,37.08,127.00",
    ) {
        var now = 0L
        val clock = object : Clock {
            override fun nowMs() = now
            override fun hourOfDay() = hour
        }
        val vehicle = SimVehicleGateway()
        val navi = SimNaviGateway()
        val speech = Speech(reply, hold)
        var ui = UiState()
        val c = ShimpyoController(
            vehicle, navi, speech, clock, ShimpyoConfig(timeScale = 60), Random(11), preDrive, teamPreference,
        ) { ui = it }

        init {
            c.attach()
            c.setShelters(ShelterDirectory.parseCsv(csv))
            navi.simulateLocation(GeoPoint(36.99, 127.0))
            navi.simulateLocation(GeoPoint(37.00, 127.0))
        }

        /** 시연 배속 60배: 한 번에 주행 1분. */
        fun minutes(n: Int) = repeat(n) { now += 1000; c.tick() }

        fun logs(): List<String> = ui.logs.map { it.text }
    }

    companion object {
        /** 수면 점검은 평범하게(7시간, 7시 기상, KSS 3), 나머지는 “응”. */
        fun standard(q: String): Pair<String, Long?> = when {
            q.contains("몇 시간 주무셨") -> "일곱 시간" to 1200L
            q.contains("일어나셨") -> "일곱 시" to 1200L
            q.contains("1부터 9까지") -> "3" to 1200L
            else -> "응" to 1200L
        }
    }

    // ── 동승자: 수면 점검은 그대로, 같이하기는 묻기 ───────────────────────────────

    @Test
    fun withPassengerSleepCheckStaysAndSoloMeansQuiet() {
        val r = Rig(reply = { q -> if (q.contains("같이 말놀이 하실래요")) "혼자 할게" to 1500L else standard(q) })
        r.vehicle.setPassenger(true)
        r.c.startTrip()
        r.minutes(100)
        // 수면 점검 세 가지 + 같이하기 질문 하나. 그 뒤로는 경고 전까지 아무것도 묻지 않는다.
        assertEquals(4, r.speech.asked.size)
        assertTrue(r.speech.asked[0].contains("몇 시간 주무셨"))
        assertTrue(r.speech.asked[3].contains("같이 말놀이 하실래요"))
        assertTrue(r.speech.spoken.first().contains("동승자분도 계시네요"))
        assertEquals(7.0, r.ui.sleepHours!!, 1e-9)
        assertTrue(r.ui.quietMode)
        assertEquals(PlayMode.SOLO, r.ui.playMode)
        assertTrue(!r.vehicle.cabin.freshAirBoost)

        var guard = 0
        while (r.ui.phase != Phase.EN_ROUTE && guard++ < 40) r.minutes(1)
        assertEquals(Phase.EN_ROUTE, r.ui.phase)
        assertTrue("연속 주행 120분(경고)에서 처음 권한다: ${r.ui.drivingMinutes}", r.ui.drivingMinutes >= 120)
        assertTrue(r.speech.asked.last().contains("운전하신 지 2시간 됐어요"))
        assertTrue(r.speech.spoken.any { it == "바깥 공기를 조금 넣을게요." })
        assertTrue(r.vehicle.cabin.freshAirBoost)
    }

    @Test
    fun shortSleepIsCaughtEvenWithPassenger() {
        val r = Rig(reply = { q ->
            when {
                q.contains("몇 시간 주무셨") -> "세 시간" to 1200L
                q.contains("같이 말놀이") -> "혼자 할게" to 1200L
                else -> standard(q)
            }
        })
        r.vehicle.setPassenger(true)
        r.c.startTrip()
        r.minutes(3)
        assertEquals(3.0, r.ui.sleepHours!!, 1e-9)
        assertTrue("4시간 미만 수면은 동승자가 있어도 처음부터 경고", r.ui.level >= RiskLevel.WARNING)
    }

    @Test
    fun passengerBoardingDuringSleepCheckDoesNotCutIt() {
        lateinit var r: Rig
        r = Rig(reply = { q ->
            if (q.contains("몇 시간 주무셨")) r.vehicle.setPassenger(true)
            if (q.contains("같이 말놀이")) "혼자 할게" to 1200L else standard(q)
        })
        r.c.startTrip()
        r.minutes(3)
        assertTrue(r.speech.asked.count { it.contains("주무셨") || it.contains("일어나셨") || it.contains("1부터 9까지") } == 3)
        assertTrue(r.speech.asked.any { it.contains("같이 말놀이 하실래요") })
        assertEquals(3, r.ui.kss)
    }

    // ── 같이하기 ─────────────────────────────────────────────────────────────

    @Test
    fun teamModeTakesTurnsAndOnlyDriverAnswersCount() {
        // 동승자는 아주 느리게(9초) 답한다. 졸음 판단에 들어가면 대답 속도가 크게 느려진 것으로 보일 것이다.
        val r = Rig(
            teamPreference = TeamPreference.ALWAYS_TOGETHER,
            reply = { q -> if (q.startsWith("동승자분 차례예요")) "응" to 9_000L else standard(q) },
        )
        r.vehicle.setPassenger(true)
        r.c.startTrip()
        r.minutes(120)
        assertEquals(PlayMode.TEAM, r.ui.playMode)
        assertTrue(r.speech.spoken.any { it.contains("같이 해요") })
        val driverTurns = r.speech.asked.count { it.startsWith("운전자분 차례예요") }
        val passengerTurns = r.speech.asked.count { it.startsWith("동승자분 차례예요") }
        assertTrue("번갈아 묻는다: 운전자 $driverTurns, 동승자 $passengerTurns", driverTurns >= 2 && passengerTurns >= 2)
        val ratio = r.ui.latencyRatio
        assertTrue("동승자의 느린 대답은 졸음 판단에 들어가지 않는다: $ratio", ratio == null || ratio < 1.5)
    }

    @Test
    fun voiceSwitchTurnsTeamOnWithoutSeatSensor() {
        var switched = false
        val r = Rig(reply = { q ->
            when {
                // 착좌 센서가 없는 차: 첫 놀이 질문에 “같이 할게”라고 말한다.
                !switched && !q.contains("주무셨") && !q.contains("일어나셨") && !q.contains("1부터 9까지") -> {
                    switched = true
                    "같이 할게" to 1200L
                }
                else -> standard(q)
            }
        }, hour = 2)
        r.c.startTrip()
        var guard = 0
        while (r.ui.playMode != PlayMode.TEAM && guard++ < 120) r.minutes(1)
        assertEquals(PlayMode.TEAM, r.ui.playMode)
        r.minutes(30)
        assertTrue(r.speech.asked.any { it.startsWith("동승자분 차례예요") })
    }

    @Test
    fun teamPreferenceChangesOnlyWhenParked() {
        val r = Rig()
        assertTrue(!r.c.setTeamPreference(TeamPreference.ALWAYS_TOGETHER))
        assertEquals(TeamPreference.ASK, r.ui.teamPreference)
        r.vehicle.setParked(true)
        assertTrue(r.c.setTeamPreference(TeamPreference.ALWAYS_TOGETHER))
        assertEquals(TeamPreference.ALWAYS_TOGETHER, r.ui.teamPreference)
    }

    @Test
    fun passengerTouchOnDriverTurnIsAFoul() {
        val r = Rig(
            teamPreference = TeamPreference.ALWAYS_TOGETHER,
            hold = { q -> q.startsWith("운전자분 차례예요") },
        )
        r.vehicle.setPassenger(true)
        r.c.startTrip()
        var guard = 0
        while (r.speech.pending == null && guard++ < 150) r.minutes(1)
        val driverAnswer = r.speech.pending!!
        r.c.passengerTouchAnswer("O")
        assertEquals(1, r.ui.fouls)
        assertEquals(Turn.DRIVER, r.ui.turn)
        r.speech.pending = null
        driverAnswer("응", 1200L)
        assertTrue(r.speech.spoken.any { it.contains("반칙이라, 팀 점수 1점 빼요") })
    }

    @Test
    fun teamModeWeighsContextUpAndConversationDown() {
        val solo = RiskEngine.evaluate(RiskEngine.Input(90, 10, latencyRatio = 2.0))
        val team = RiskEngine.evaluate(RiskEngine.Input(90, 10, latencyRatio = 2.0, teamMode = true))
        // 혼자: 35 + 30 = 65, 같이하기: 35×1.2 + 30×0.5 = 42 + 15 = 57
        assertEquals(65, solo.score)
        assertEquals(57, team.score)
    }

    @Test
    fun parsesTeamIntents() {
        assertEquals(KoreanText.TeamIntent.TOGETHER, KoreanText.teamIntentOf("같이 할게"))
        assertEquals(KoreanText.TeamIntent.TOGETHER, KoreanText.teamIntentOf("우리 함께 하자"))
        assertEquals(KoreanText.TeamIntent.SOLO, KoreanText.teamIntentOf("혼자 할게"))
        assertEquals(KoreanText.TeamIntent.SOLO, KoreanText.teamIntentOf("같이 안 할래"))
        assertNull(KoreanText.teamIntentOf("응"))
        assertNull(KoreanText.teamIntentOf("이십칠"))
    }

    // ── 차량 특화 놀이 ───────────────────────────────────────────────────────

    private val regionCsv = """
        졸음쉼터명,시도명,시군구명,도로노선명,위도,경도,주차면수
        칠곡,경상북도,칠곡군,대구외곽순환선,35.9049,128.4795,15
        왜관,경상북도,칠곡군,경부선,35.99,128.40,22
        성주,경상북도,성주군,중부내륙선,35.92,128.28,8
        오창,충청북도,청주시,중부선,36.73,127.45,14
    """.trimIndent()

    @Test
    fun regionQuizAnswersComeFromData() {
        val dir = ShelterDirectory.parseCsv(regionCsv)
        val nearby = dir.nearby(GeoPoint(35.905, 128.48), maxKm = 40.0, limit = 3)
        assertEquals("칠곡", nearby.first().shelter.name)
        repeat(30) { seed ->
            val game = RegionQuizGame.build(nearby, dir.shelters, Random(seed))!!
            repeat(game.size) {
                val q = game.nextPrompt()
                // 정답 확인: 문장 속 쉼터의 실제 데이터와 맞는지 다시 계산한다.
                val s = dir.shelters.first { q.contains("${it.name} 졸음쉼터") }
                val truth = when {
                    q.contains("대보다 많이") -> s.parkingSpaces!! > 10
                    q.contains("선에 있다") -> q.contains("${s.road}에 있다")
                    q.contains("${s.province} ") && dir.shelters.any { o -> q.contains(" ${o.district}에 있다") } ->
                        q.contains(" ${s.district}에 있다")
                    else -> q.contains("${s.province}에 있다")
                }
                val reaction = game.react(if (truth) "맞아" else "틀려")
                assertTrue("[$seed] $q → $reaction", reaction.startsWith("정답!"))
                assertTrue(game.lastCorrect == true)
            }
            assertTrue(!game.graded)
        }
    }

    /** 실제 데이터 253곳 전체로 문제를 만들어, 같은 이름의 쉼터 모두에 대해 정답이 맞는지 확인한다. */
    @Test
    fun regionQuizIsUnambiguousOnRealData() {
        val file = listOf("src/main/assets/shelters.csv", "app/src/main/assets/shelters.csv", "shimpyo/app/src/main/assets/shelters.csv")
            .map { java.io.File(it) }.first { it.exists() }
        val dir = ShelterDirectory.parseCsv(file.readText(Charsets.UTF_8))
        val routes = dir.shelters.map { it.road }.toSet()
        var checked = 0
        for ((i, s) in dir.shelters.withIndex()) {
            val game = RegionQuizGame.build(dir.nearby(s.point, maxKm = 40.0, limit = 3), dir.shelters, Random(i)) ?: continue
            repeat(game.size) {
                val q = game.nextPrompt().substringAfter("답해 주세요. ")
                val name = q.substringBefore(" 졸음쉼터")
                val same = dir.shelters.filter { it.name == name }
                val truth: Boolean = if (q.contains("대보다 많이")) {
                    val all = same.map { it.parkingSpaces!! > 10 }.distinct()
                    assertTrue("주차 문제는 같은 이름 전체가 같은 답이어야 한다: $q", all.size == 1)
                    all.single()
                } else {
                    val place = q.substringAfter("졸음쉼터는 ").substringBefore("에 있다")
                    val all = same.map { t ->
                        when {
                            place in routes -> t.road == place
                            place.contains(" ") -> place == "${t.province} ${t.district}"
                            else -> t.province == place
                        }
                    }.distinct()
                    assertTrue("같은 이름 전체가 같은 답이어야 한다: $q", all.size == 1)
                    all.single()
                }
                assertTrue("$q", game.react(if (truth) "맞아" else "틀려").startsWith("정답!"))
                checked++
            }
        }
        assertTrue("문제를 충분히 만들었는지: $checked", checked > 500)
    }

    @Test
    fun distanceGuessToleratesSmallErrors() {
        val dir = ShelterDirectory.parseCsv(regionCsv)
        val here = GeoPoint(35.80, 128.48)
        val ahead = dir.aheadList(here, headingDeg = 0.0, limit = 2)
        val game = DistanceGuessGame.build(ahead)!!
        game.nextPrompt()
        val km = ahead.first().distanceKm
        assertTrue(game.react("${km.toInt()}킬로").contains("킬로미터"))
        assertTrue(game.lastCorrect == true)
        game.nextPrompt()
        game.react("300")
        assertTrue(game.lastCorrect == false)
        assertTrue(!game.graded)
    }

    @Test
    fun firstGameIsAVehicleGameWhenLocationIsKnown() {
        val r = Rig(hour = 2)
        r.c.startTrip()
        var guard = 0
        while (r.logs().none { it.startsWith("대화 시작: ") && !it.contains("수면 점검") && !it.contains("졸림") } && guard++ < 120) r.minutes(1)
        val first = r.logs().first { it.startsWith("대화 시작: ") && !it.contains("수면 점검") && !it.contains("졸림") }
        assertTrue(first, first.contains("쉼터 거리 맞히기") || first.contains("지역 O/X"))
    }

    // ── 무입력 출발 ──────────────────────────────────────────────────────────

    @Test
    fun wearableSleepSkipsDepartureQuestions() {
        val source = SimPreDriveSource().apply { sleep = SleepRecord(6.5, 7.0, "갤럭시 워치") }
        val r = Rig(hour = 10, preDrive = source)
        r.c.startTrip()
        r.minutes(3)
        assertTrue("출발 전 질문 없음: ${r.speech.asked}", r.speech.asked.isEmpty())
        assertTrue(r.speech.spoken.first().contains("갤럭시 워치 기록으로 지난밤 6시간 30분"))
        assertEquals("갤럭시 워치", r.ui.sleepSource)
        assertEquals(6.5, r.ui.sleepHours!!, 1e-9)
        assertTrue(r.ui.hoursAwake!! in 3.0..3.2)
    }

    @Test
    fun wearableShortSleepStillTriggersEarlyOffer() {
        val source = SimPreDriveSource().apply { sleep = SleepRecord(3.0, 5.0, "애플 워치") }
        val r = Rig(preDrive = source)
        r.c.startTrip()
        var guard = 0
        while (r.ui.phase != Phase.EN_ROUTE && guard++ < 10) r.minutes(1)
        assertEquals(Phase.EN_ROUTE, r.ui.phase)
        assertTrue(r.speech.asked.none { it.contains("주무셨") || it.contains("일어나셨") })
        assertTrue(r.speech.asked.last().contains("쉬었다 갈까요"))
        assertTrue(r.ui.drivingMinutes < 5)
    }

    @Test
    fun sleepPrepLevelsFollowSleepAndWakeTime() {
        assertEquals(CabinPrep.NONE, SleepPrep.plan(7.5, 4.0).prep)
        assertEquals(CabinPrep.REFRESH, SleepPrep.plan(5.5, 4.0).prep)
        assertEquals(CabinPrep.ALERT, SleepPrep.plan(4.5, 4.0).prep)
        // 잠은 충분해도 17시간 넘게 깨어 있으면 강하게
        assertEquals(CabinPrep.ALERT, SleepPrep.plan(8.0, 18.0).prep)
        assertNull(SleepPrep.announcement(SleepPrep.plan(7.5, 4.0)))
    }

    @Test
    fun wearableShortSleepPreparesCabinAfterAnnouncing() {
        val source = SimPreDriveSource().apply { sleep = SleepRecord(4.5, 6.0, "갤럭시 워치") }
        val r = Rig(hour = 10, preDrive = source)
        r.c.startTrip()
        r.minutes(1)
        val first = r.speech.spoken.first()
        assertTrue(first, first.contains("바깥 공기를 넣고 실내를 21도로") && first.contains("1시간쯤 지나면"))
        assertEquals(true, r.vehicle.cabin.freshAirBoost)
        assertEquals(21f, r.vehicle.cabin.prepCelsius)
        assertEquals(CabinPrep.ALERT, r.ui.cabinPrep)
        assertEquals(60, r.ui.plannedRestAtMinute)
        // 운행을 마치면 출발 때 바꾼 실내를 되돌린다.
        r.c.endTrip()
        assertNull(r.vehicle.cabin.prepCelsius)
        assertEquals(false, r.vehicle.cabin.freshAirBoost)
    }

    @Test
    fun wearableSlightlyShortSleepOnlyLowersTemperature() {
        val source = SimPreDriveSource().apply { sleep = SleepRecord(5.5, 6.0, "갤럭시 워치") }
        val r = Rig(hour = 10, preDrive = source)
        r.c.startTrip()
        r.minutes(1)
        assertEquals(22f, r.vehicle.cabin.prepCelsius)
        assertEquals(false, r.vehicle.cabin.freshAirBoost)
        assertNull(r.ui.plannedRestAtMinute)
    }

    @Test
    fun restRestoresPrepTemperatureBeforeRestClimate() {
        val source = SimPreDriveSource().apply { sleep = SleepRecord(5.5, 6.0, "갤럭시 워치") }
        val r = Rig(hour = 10, preDrive = source)
        r.c.startTrip()
        r.minutes(1)
        r.vehicle.setParked(true)
        r.c.requestRestNow()
        r.minutes(1)
        assertNull("휴식 전에 출발 온도를 되돌림", r.vehicle.cabin.prepCelsius)
        assertEquals(true, r.vehicle.cabin.restClimate)
    }

    @Test
    fun silenceAtRestOfferIsNotARefusalAndAsksAgainSooner() {
        val source = SimPreDriveSource().apply { sleep = SleepRecord(3.0, 5.0, "애플 워치") }
        val r = Rig(preDrive = source, reply = { q -> if (q.contains("쉬었다 갈까요")) "" to null else standard(q) })
        r.c.startTrip()
        var guard = 0
        while (r.logs().none { it.contains("쉼터 제안에 대답 없음") } && guard++ < 15) r.minutes(1)
        assertTrue(r.logs().toString(), r.logs().any { it.contains("쉼터 제안에 대답 없음") })
        assertEquals("대답이 없으면 거절로 세지 않는다", 0, r.ui.declinedOffers)
        assertTrue(r.speech.spoken.any { it.startsWith("대답이 없으셔서 걱정돼요") })
        assertTrue(r.speech.spoken.none { it.startsWith("알겠어요. 대신 대화를") })
        // 거절(10분)보다 짧게, 3분쯤 뒤 다시 권한다.
        val offers = { r.speech.asked.count { it.contains("쉬었다 갈까요") && !it.startsWith("쉬었다 갈까요?") } }
        val before = offers()
        r.minutes(5)
        assertTrue("다시 권함: ${r.speech.asked}", offers() > before)
    }

    @Test
    fun calendarPlansFirstRest() {
        assertEquals(110, ShimpyoController.plannedRestMinute(PlannedDrive("출장", 240, night = false)))
        assertEquals(90, ShimpyoController.plannedRestMinute(PlannedDrive("야간 배송", 150, night = true)))
        assertNull(ShimpyoController.plannedRestMinute(PlannedDrive("장보기", 40, night = false)))

        val source = SimPreDriveSource().apply { plan = PlannedDrive("부산 출장", 240, night = true) }
        val r = Rig(preDrive = source)
        r.c.startTrip()
        assertEquals(90, r.ui.plannedRestAtMinute)
        var guard = 0
        while (r.ui.phase != Phase.EN_ROUTE && guard++ < 130) r.minutes(1)
        assertEquals(Phase.EN_ROUTE, r.ui.phase)
        assertTrue(r.speech.spoken.first().contains("심야 부산 출장 일정"))
        assertTrue(r.speech.asked.last().startsWith("계획한 휴식 시간이에요."))
        assertTrue("계획한 시점(90분) 무렵에 권한다: ${r.ui.drivingMinutes}", r.ui.drivingMinutes in 90..100)
        assertNull(r.ui.plannedRestAtMinute)
    }
}
