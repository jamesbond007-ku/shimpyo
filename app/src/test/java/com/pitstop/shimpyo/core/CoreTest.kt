package com.pitstop.shimpyo.core

import com.pitstop.shimpyo.core.sim.SimNaviGateway
import com.pitstop.shimpyo.core.sim.SimVehicleGateway
import com.pitstop.shimpyo.core.talk.KoreanText
import com.pitstop.shimpyo.core.talk.WordChainGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RiskEngineTest {
    @Test
    fun drivingTimeAloneRaisesLevelStepByStep() {
        fun level(min: Int) = RiskEngine.evaluate(RiskEngine.Input(min, 10, null)).level
        assertEquals(RiskLevel.NORMAL, level(30))
        assertEquals(RiskLevel.NORMAL, level(60))
        assertEquals(RiskLevel.CAUTION, level(90))
        assertEquals(RiskLevel.WARNING, level(120))
    }

    @Test
    fun nightAndSlowResponseReachRestAdvice() {
        val r = RiskEngine.evaluate(RiskEngine.Input(continuousDrivingMinutes = 100, hourOfDay = 2, latencyRatio = 2.1))
        assertEquals(RiskLevel.REST_ADVISED, r.level)
        assertEquals(3, r.reasons.size)
    }

    @Test
    fun oneMissedAnswerIsMildTwoInARowIsStrong() {
        fun score(streak: Int) = RiskEngine.evaluate(RiskEngine.Input(0, 10, null, noResponseStreak = streak)).score
        assertEquals(15, score(1))
        assertEquals(35, score(2))
    }

    @Test
    fun circadianPeakIsTwoToSixAm() {
        fun t(h: Int) = RiskEngine.evaluate(RiskEngine.Input(0, h, null)).score
        assertEquals(20, t(3))
        assertEquals(15, t(0))
        assertEquals(15, t(6))
        assertEquals(10, t(14))
        assertEquals(5, t(13))
        assertEquals(0, t(10))
    }

    @Test
    fun oneSlowAnswerAloneIsNotCompared() {
        val b = LatencyBaseline(baselineSamples = 2, windowSize = 3)
        b.record(1000); b.record(1000)
        b.record(3000)
        assertNull(b.ratio())
        b.record(3000)
        assertEquals(3.0, b.ratio()!!, 0.01)
    }

    @Test
    fun trackerDropsOnlyAfterRepeatedLowerLevels() {
        val t = RiskTracker(downHold = 3)
        t.update(RiskLevel.WARNING)
        assertEquals(RiskLevel.WARNING, t.update(RiskLevel.NORMAL))
        assertEquals(RiskLevel.WARNING, t.update(RiskLevel.NORMAL))
        assertEquals(RiskLevel.NORMAL, t.update(RiskLevel.NORMAL))
    }

    @Test
    fun latencyBaselineComparesWithTodaysOwnSpeed() {
        val b = LatencyBaseline(baselineSamples = 2, windowSize = 3)
        b.record(1000); b.record(1200)
        assertEquals(1100L, b.baselineMs)
        assertNull(b.ratio())
        b.record(2200); b.record(2200); b.record(2400)
        assertEquals(2.0, b.ratio()!!, 0.01)
        b.record(null)
        assertTrue(b.lastNoResponse)
    }
}

class NapCapsuleTest {
    @Test
    fun wakeStepsBrightenInTheLastThreeMinutes() {
        assertNull(NapCapsule.stepAt(181, 15))
        assertEquals(NapCapsule.WakeStep.DAWN, NapCapsule.stepAt(180, 15))
        assertEquals(NapCapsule.WakeStep.DAWN, NapCapsule.stepAt(121, 15))
        assertEquals(NapCapsule.WakeStep.MORNING, NapCapsule.stepAt(120, 15))
        assertEquals(NapCapsule.WakeStep.DAY, NapCapsule.stepAt(60, 15))
        assertEquals(NapCapsule.WakeStep.DAY, NapCapsule.stepAt(1, 15))
        assertNull(NapCapsule.stepAt(0, 15))
    }

    @Test
    fun shortRestSkipsEarlySteps() {
        // 3분 휴식이면 2분 전부터, 앞 단계(새벽빛)는 건너뛴다.
        assertNull(NapCapsule.stepAt(121, 3))
        assertEquals(NapCapsule.WakeStep.MORNING, NapCapsule.stepAt(120, 3))
        assertEquals(NapCapsule.WakeStep.DAY, NapCapsule.stepAt(30, 3))
        assertNull(NapCapsule.stepAt(30, 1))
    }
}

class DemoGameTitleTest {
    @Test
    fun demoChipTitlesMatchTheGamesTheyStart() {
        val deck = com.pitstop.shimpyo.core.talk.TalkDeck(Random(1))
        for (g in ShimpyoController.DemoGame.entries) {
            val kind = g.kind ?: continue
            assertEquals(g.label, deck.create(kind).title, g.gameTitle)
        }
    }
}

class PolylineTest {
    @Test
    fun decodesGoogleEncodedPolyline() {
        // 구글 문서 예: _p~iF~ps|U_ulLnnqC_mqNvxq`@ → (38.5,-120.2) (40.7,-120.95) (43.252,-126.453)
        val pts = com.pitstop.shimpyo.ui.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].lat, 1e-5); assertEquals(-120.2, pts[0].lon, 1e-5)
        assertEquals(43.252, pts[2].lat, 1e-5); assertEquals(-126.453, pts[2].lon, 1e-5)
    }
}

class SleepPrepWordingTest {
    @Test
    fun alertSaysWhyLongAwakeIsNotCalledShortSleep() {
        val longAwake = SleepPrep.announcement(SleepPrep.plan(7.5, 20.0))!!
        assertTrue(longAwake, longAwake.startsWith("일어나신 지 오래돼서"))
        val short = SleepPrep.announcement(SleepPrep.plan(4.0, 8.0))!!
        assertTrue(short, short.startsWith("잠이 많이 부족한 날이라"))
    }
}

class DemoAnswerTest {
    @Test
    fun demoAnswerIsJudgedCorrectByEveryGame() {
        val r = Random(7)
        val graded = listOf(
            com.pitstop.shimpyo.core.talk.ArithmeticGame(r), com.pitstop.shimpyo.core.talk.GuGuDanGame(r),
            com.pitstop.shimpyo.core.talk.ChosungQuizGame(r), com.pitstop.shimpyo.core.talk.OxQuizGame(r),
            com.pitstop.shimpyo.core.talk.ReverseDigitsGame(r), com.pitstop.shimpyo.core.talk.MemoryGame(r),
        )
        repeat(20) {
            for (g in graded) {
                g.nextPrompt()
                val answer = g.demoAnswer()!!
                g.react(answer)
                if (g.lastCorrect != null) assertTrue("${g.title}: $answer", g.lastCorrect == true)
            }
        }
        val nonsense = com.pitstop.shimpyo.core.talk.NonsenseQuizGame(r)
        repeat(10) { nonsense.nextPrompt(); assertTrue(nonsense.react(nonsense.demoAnswer()).startsWith("딩동댕")) }
        val chain = WordChainGame(r)
        repeat(10) { chain.nextPrompt(); val w = chain.demoAnswer() ?: return@repeat; assertTrue(chain.react(w).contains("좋아요")) }
    }
}

class KoreanTextFixesTest {
    @Test
    fun refusalsWithGaRaeAreNo() {
        assertEquals(KoreanText.Intent.NO, KoreanText.intentOf("그냥 갈래"))
        assertEquals(KoreanText.Intent.NO, KoreanText.intentOf("계속 갈게"))
        assertEquals(KoreanText.Intent.NO, KoreanText.intentOf("안 갈래"))
        assertEquals(KoreanText.Intent.YES, KoreanText.intentOf("응 쉬었다 가자"))
        assertEquals(KoreanText.Intent.YES, KoreanText.intentOf("응응"))
        assertEquals(KoreanText.Intent.YES, KoreanText.intentOf("네 좋아요"))
        assertEquals(KoreanText.Intent.UNKNOWN, KoreanText.intentOf("어디야?"))
        assertEquals(KoreanText.Intent.UNKNOWN, KoreanText.intentOf("어떻게?"))
    }

    @Test
    fun clockPrefersWordNextToHour() {
        assertEquals(9.0, KoreanText.parseClock("어젯밤 늦게 자서 아침 9시에 일어났어")!!.hour, 0.01)
        assertEquals(0.0, KoreanText.parseClock("밤 12시")!!.hour, 0.01)
        assertEquals(14.0, KoreanText.parseClock("오후 2시")!!.hour, 0.01)
    }

    @Test
    fun digitsIgnorePoliteEnding() {
        assertEquals(listOf(4, 7, 1), KoreanText.parseDigits("사 칠 일이요"))
    }
}

class KoreanTextTest {
    @Test
    fun initialSoundRule() {
        assertTrue('역' in KoreanText.allowedStarts('력'))
        assertTrue('이' in KoreanText.allowedStarts('리'))
        assertTrue('나' in KoreanText.allowedStarts('라'))
        assertTrue('여' in KoreanText.allowedStarts('녀'))
        assertEquals(setOf('기'), KoreanText.allowedStarts('기'))
    }

    @Test
    fun intents() {
        assertEquals(KoreanText.Intent.YES, KoreanText.intentOf("응"))
        assertEquals(KoreanText.Intent.YES, KoreanText.intentOf("그래 쉬었다 가자"))
        assertEquals(KoreanText.Intent.NO, KoreanText.intentOf("아니 괜찮아"))
        assertEquals(KoreanText.Intent.NO, KoreanText.intentOf("그냥 가자"))
        assertEquals(KoreanText.Intent.CANCEL, KoreanText.intentOf("취소해 줘"))
        assertEquals(KoreanText.Intent.UNKNOWN, KoreanText.intentOf("오늘 날씨 좋네"))
    }

    @Test
    fun numbers() {
        assertEquals(15, KoreanText.parseNumber("15"))
        assertEquals(15, KoreanText.parseNumber("십오"))
        assertEquals(15, KoreanText.parseNumber("열다섯"))
        assertEquals(52, KoreanText.parseNumber("오십이요"))
        assertEquals(42, KoreanText.parseNumber("마흔둘"))
        assertEquals(9, KoreanText.parseNumber("아홉"))
        assertNull(KoreanText.parseNumber("모르겠어"))
    }

    @Test
    fun particles() {
        assertEquals("가", KoreanText.subjectParticle("졸음쉼터"))
        assertEquals("이", KoreanText.subjectParticle("휴게소 주차장"))
        assertEquals("를", KoreanText.objectParticle("쉼터"))
        assertEquals("을", KoreanText.objectParticle("목감"))
    }

    @Test
    fun wordChainAcceptsValidAnswerAndContinues() {
        val game = WordChainGame(Random(1))
        val first = game.nextPrompt()
        val word = first.substringAfterLast(", ").trim('!', '.', ' ')
        val need = KoreanText.lastSyllable(word)!!
        val reply = game.react("${need}하늘")
        assertTrue(reply, reply.contains("좋아요") || reply.contains("졌네요"))
    }
}

class ShelterTest {
    private val csv = """
        졸음쉼터명,시도명,도로노선명,도로노선방향,위도,경도,주차면수
        북쪽쉼터,테스트,테스트선,상행,37.10,127.00,20
        남쪽쉼터,테스트,테스트선,하행,36.90,127.00,20
        "쉼표, 따옴표 쉼터",테스트,테스트선,상행,37.30,127.00,10
        좌표없음,테스트,테스트선,상행,,,5
    """.trimIndent()

    @Test
    fun parsesHeaderByNameAndSkipsBadRows() {
        val dir = ShelterDirectory.parseCsv(csv)
        assertEquals(3, dir.size)
        assertEquals("쉼표, 따옴표 쉼터", dir.shelters[2].name)
    }

    @Test
    fun bundledPublicDataLoads() {
        // Gradle 단위 테스트는 app/ 폴더에서, 수동 실행은 저장소 루트에서 돈다.
        val file = listOf("src/main/assets/shelters.csv", "app/src/main/assets/shelters.csv", "shimpyo/app/src/main/assets/shelters.csv")
            .map { java.io.File(it) }.first { it.exists() }
        val dir = ShelterDirectory.parseCsv(file.readText(Charsets.UTF_8))
        assertEquals(253, dir.size)
        val geumnam = dir.shelters.first { it.name == "금남" }
        assertEquals(36.45062776, geumnam.point.lat, 1e-8)
        assertEquals("금남 졸음쉼터", geumnam.spokenName)
    }

    @Test
    fun picksShelterOnOwnSideOfRoad() {
        // 남북으로 난 도로의 동쪽(오른쪽, 북행 기준)과 서쪽에 같은 이름의 쉼터가 마주 보고 있다.
        val east = Shelter("마주", GeoPoint(37.05, 127.0004))
        val west = Shelter("마주", GeoPoint(37.05, 126.9996))
        val dir = ShelterDirectory(listOf(west, east))
        val here = GeoPoint(37.0, 127.0)
        assertEquals(east, dir.nearestAhead(here, headingDeg = 0.0)!!.shelter)      // 북쪽으로 달리면 동쪽 쉼터
        val south = GeoPoint(37.10, 127.0)
        assertEquals(west, dir.nearestAhead(south, headingDeg = 180.0)!!.shelter)   // 남쪽으로 달리면 서쪽 쉼터
    }

    @Test
    fun picksNearestShelterAheadOnly() {
        val dir = ShelterDirectory.parseCsv(csv)
        val here = GeoPoint(37.0, 127.0)
        assertEquals("북쪽쉼터", dir.nearestAhead(here, headingDeg = 0.0)!!.shelter.name)
        assertEquals("남쪽쉼터", dir.nearestAhead(here, headingDeg = 180.0)!!.shelter.name)
        assertNull(dir.nearestAhead(here, headingDeg = 90.0))
    }
}

/** 컨트롤러 전체 흐름: 출발 → 대화 → 쉼터 제안 수락 → 도착 → 휴식 → 재출발 */
class ControllerFlowTest {

    private class FakeClock(var now: Long = 0, var hour: Int = 23) : Clock {
        override fun nowMs() = now
        override fun hourOfDay() = hour
    }

    /** 대본대로 즉시 대답하는 가짜 음성. */
    private class ScriptedSpeech : SpeechGateway {
        override val backend = Backend.SIM
        override var canListen = true
        val spoken = mutableListOf<String>()
        var answer: () -> Pair<String, Long?> = { "응" to 1000L }
        override fun start() = Unit
        override fun stop() = Unit
        override fun speak(text: String, onDone: () -> Unit) { spoken += text; onDone() }
        override fun listen(timeoutMs: Long, onResult: (String, Long?) -> Unit) {
            val (t, l) = answer(); onResult(t, l)
        }
        override fun cancel() = Unit
    }

    private val clock = FakeClock()
    private val speech = ScriptedSpeech()
    private val vehicle = SimVehicleGateway()
    private val navi = SimNaviGateway()
    private var ui = UiState()
    private val controller = ShimpyoController(
        vehicle, navi, speech, clock,
        ShimpyoConfig(timeScale = 60, restMinutes = 15, ventSeconds = 20),
        Random(7),
    ) { ui = it }

    private fun tick(seconds: Int = 1) {
        repeat(seconds) {
            clock.now += 1000
            controller.tick()
        }
    }

    private fun setUpRoute() {
        controller.attach()
        controller.setShelters(
            ShelterDirectory.parseCsv("졸음쉼터명,위도,경도\n앞쪽졸음쉼터,37.08,127.00\n뒤쪽졸음쉼터,36.95,127.00"),
        )
        navi.simulateLocation(GeoPoint(36.99, 127.0))
        navi.simulateLocation(GeoPoint(37.00, 127.0)) // 북쪽으로 진행 중
    }

    @Test
    fun fullRestCycle() {
        setUpRoute()
        controller.startTrip()
        tick() // 출발 인사로 기준값 측정
        assertEquals(1000L, ui.baselineMs)

        // 이후 응답이 점점 느려진다.
        speech.answer = { "응" to 2600L }
        var guard = 0
        while (ui.phase != Phase.EN_ROUTE && guard++ < 300) tick()
        assertEquals(Phase.EN_ROUTE, ui.phase)
        assertEquals("앞쪽졸음쉼터", navi.restStop?.name)
        assertTrue(speech.spoken.any { it.contains("앞쪽졸음쉼터가 있어요") })
        assertFalse("주행 중에는 시트를 움직이지 않는다", vehicle.cabin.seatReclined)

        navi.simulateArrival()
        assertEquals(Phase.WAITING_PARK, ui.phase)
        vehicle.setParked(true)
        tick()
        assertEquals(Phase.RESTING, ui.phase)
        assertTrue(vehicle.cabin.seatReclined)
        assertTrue(vehicle.cabin.cabinDim)
        assertTrue(vehicle.cabin.restClimate)

        tick(15) // 15분(배속 60)
        assertEquals(Phase.WAKING, ui.phase)
        assertTrue(vehicle.cabin.windowOpen)

        tick(21)
        assertEquals(Phase.MONITORING, ui.phase)
        assertFalse(vehicle.cabin.windowOpen)
        assertFalse(vehicle.cabin.seatReclined)
        assertFalse(vehicle.cabin.cabinDim)
        assertFalse(vehicle.cabin.restClimate)
        assertNull(navi.restStop)
        assertTrue(ui.drivingMinutes <= 1)
    }

    @Test
    fun napCapsuleWakesWithLightThenVoiceThenSeat() {
        setUpRoute()
        controller.startTrip()
        tick()
        speech.answer = { "응" to 2600L }
        var guard = 0
        while (ui.phase != Phase.EN_ROUTE && guard++ < 300) tick()
        navi.simulateArrival()
        vehicle.setParked(true)
        tick()
        assertEquals(Phase.RESTING, ui.phase)
        assertTrue(speech.spoken.any { it.contains("커피") && it.contains("낮잠 캡슐") })
        assertNull(vehicle.cabin.wakeLight)
        val spokenAtRest = speech.spoken.size

        tick(12) // 3분 남음 → 새벽빛
        assertEquals(NapCapsule.WakeStep.DAWN, ui.wakeStep)
        assertEquals(0.4f, vehicle.cabin.wakeLight!!, 0.001f)
        assertEquals(22f, vehicle.cabin.restCelsius!!, 0.001f)

        tick() // 2분 남음 → 아침빛 + 시원하게
        assertEquals(0.7f, vehicle.cabin.wakeLight!!, 0.001f)
        assertEquals(NapCapsule.WAKE_CELSIUS, vehicle.cabin.restCelsius!!, 0.001f)

        tick() // 1분 남음 → 한낮빛
        assertEquals(1.0f, vehicle.cabin.wakeLight!!, 0.001f)
        assertTrue("빛으로 깨우는 동안은 말을 걸지 않는다", speech.spoken.size == spokenAtRest)
        assertTrue("등받이는 아직 눕혀 둔다", vehicle.cabin.seatReclined)

        tick() // 끝 → 목소리로 알리고 등받이를 세우고 창문을 연다
        assertEquals(Phase.WAKING, ui.phase)
        assertTrue(speech.spoken.last().startsWith("잘 쉬셨어요?"))
        assertTrue(speech.spoken.last().contains("등받이"))
        assertFalse(vehicle.cabin.seatReclined)
        assertTrue(vehicle.cabin.windowOpen)

        tick(21)
        assertEquals(Phase.MONITORING, ui.phase)
        assertFalse(vehicle.cabin.cabinDim)
        assertNull(vehicle.cabin.wakeLight)
        assertFalse(vehicle.cabin.restClimate)
        assertNull(ui.wakeStep)
        assertTrue(speech.spoken.any { it.contains("차 밖에서 1분") })
    }

    @Test
    fun drivingOffDuringRestEndsRestAndResumesMonitoring() {
        setUpRoute()
        controller.startTrip()
        tick()
        speech.answer = { "응" to 2600L }
        var guard = 0
        while (ui.phase != Phase.EN_ROUTE && guard++ < 300) tick()
        navi.simulateArrival()
        vehicle.setParked(true)
        tick()
        assertEquals(Phase.RESTING, ui.phase)
        tick(2) // 2분 쉼
        vehicle.setParked(false)
        tick()
        assertEquals("휴식 중에 출발하면 감시로 돌아간다", Phase.MONITORING, ui.phase)
        assertFalse(vehicle.cabin.cabinDim)
        assertFalse(vehicle.cabin.restClimate)
        assertNull(navi.restStop)
        assertFalse("누운 채 달리지 않게 출발하면 바로 등받이를 세운다", vehicle.cabin.seatReclined)
        assertTrue(speech.spoken.any { it.contains("등받이를 먼저 세울게요") })
    }

    @Test
    fun kss9DoesNotRepeatOfferAfterDecline() {
        setUpRoute()
        controller.startTrip()
        // 수면 점검: 수면 시간·기상 시각·KSS 9
        val answers = ArrayDeque(listOf("네 시간", "아침 여섯 시", "9"))
        speech.answer = { (answers.removeFirstOrNull() ?: "아니 괜찮아") to 1500L }
        tick(3)
        val offersAfter = { speech.spoken.count { it.contains("쉬었다 갈까요") } }
        var guard = 0
        while (ui.declinedOffers == 0 && guard++ < 60) tick()
        assertEquals(1, ui.declinedOffers)
        val before = offersAfter()
        speech.answer = { "응" to 1500L }
        tick(5) // 5분: 거절 쿨다운(10분) 안
        assertEquals("KSS 9라도 거절한 뒤 쿨다운 동안은 다시 권하지 않는다", before, offersAfter())
    }

    @Test
    fun guidanceIsDroppedWhenDriverNeverParks() {
        setUpRoute()
        controller.startTrip()
        tick()
        speech.answer = { "응" to 2600L }
        var guard = 0
        while (ui.phase != Phase.EN_ROUTE && guard++ < 300) tick()
        assertEquals(Phase.EN_ROUTE, ui.phase)
        // 주차하지 않고 계속 달린다. 20분쯤 지나면 안내를 접고 감시로 돌아간다(그 뒤 5분이 지나면 다시 권할 수 있다).
        var minutes = 0
        while (speech.spoken.none { it.contains("안내는 접을게요") } && minutes++ < 30) tick()
        assertTrue(minutes in 19..22)
        assertEquals(Phase.MONITORING, ui.phase)
        assertNull(navi.restStop)
    }

    @Test
    fun declineThenOfferAgainLater() {
        setUpRoute()
        controller.startTrip()
        tick()
        speech.answer = { "아니 괜찮아" to 2800L }
        var guard = 0
        while (ui.declinedOffers == 0 && guard++ < 300) tick()
        assertEquals(1, ui.declinedOffers)
        assertNull(navi.restStop)
        speech.answer = { "그래" to 2800L }
        guard = 0
        while (ui.phase != Phase.EN_ROUTE && guard++ < 300) tick()
        assertEquals(Phase.EN_ROUTE, ui.phase)
    }

    @Test
    fun restWaitsForParkingAndSeatFallbackSpeaks() {
        vehicle.seatSupported = false
        setUpRoute()
        controller.startTrip()
        tick()
        controller.requestRestNow()
        assertEquals(Phase.WAITING_PARK, ui.phase)
        tick(3)
        assertEquals(Phase.WAITING_PARK, ui.phase)
        vehicle.setParked(true)
        tick()
        assertEquals(Phase.RESTING, ui.phase)
        assertTrue(speech.spoken.any { it.contains("직접 눕혀") })
    }

    @Test
    fun drivingDuringRestRestoresLightAndRaisesSeat() {
        setUpRoute()
        controller.startTrip()
        tick()
        vehicle.setParked(true)
        controller.requestRestNow()
        tick()
        assertTrue(vehicle.cabin.seatReclined)
        vehicle.setParked(false)
        tick()
        assertFalse(vehicle.cabin.cabinDim)
        assertFalse("출발하면 등받이를 바로 세운다(눕히기만 주차 중에 한다)", vehicle.cabin.seatReclined)
        assertEquals(Phase.MONITORING, ui.phase)
    }

    @Test
    fun parkingAwayFromShelterAsksBeforeReclining() {
        setUpRoute()
        controller.startTrip()
        tick()
        speech.answer = { "응" to 2600L }
        var guard = 0
        while (ui.phase != Phase.EN_ROUTE && guard++ < 300) tick()
        assertEquals(Phase.EN_ROUTE, ui.phase)
        // 쉼터(37.08)에서 한참 떨어진 곳(37.00)에서 P단: 정체·주유소일 수 있다.
        speech.answer = { "아니" to 1500L }
        vehicle.setParked(true)
        tick()
        assertTrue(speech.spoken.any { it.contains("여기서 15분 쉬어 갈까요") })
        assertEquals("거절하면 쉼터 안내를 이어 간다", Phase.EN_ROUTE, ui.phase)
        assertFalse(vehicle.cabin.seatReclined)
        tick(3)
        assertEquals("같은 주차에서는 다시 묻지 않는다", 1, speech.spoken.count { it.contains("여기서 15분 쉬어 갈까요") })
        // 다시 출발했다가 쉼터에 도착해 P단을 넣으면 바로 쉰다.
        vehicle.setParked(false)
        navi.simulateLocation(GeoPoint(37.0795, 127.0))
        vehicle.setParked(true)
        tick()
        assertEquals(Phase.RESTING, ui.phase)
        assertTrue(vehicle.cabin.seatReclined)
    }

    @Test
    fun saidYesToRestHereStartsRest() {
        setUpRoute()
        controller.startTrip()
        tick()
        speech.answer = { "응" to 2600L }
        var guard = 0
        while (ui.phase != Phase.EN_ROUTE && guard++ < 300) tick()
        speech.answer = { "응 여기서 쉬자" to 1500L }
        vehicle.setParked(true)
        tick()
        assertEquals(Phase.RESTING, ui.phase)
    }

    @Test
    fun parkingMeansGearPark() {
        assertEquals("D단에서 주차 브레이크만 건 것은 주차가 아니다", false, ParkingSignal.combine(gearPark = false, parkingBrakeOn = true))
        assertEquals(true, ParkingSignal.combine(gearPark = true, parkingBrakeOn = false))
        assertEquals("기어 신호가 없으면 주차 브레이크로", true, ParkingSignal.combine(gearPark = null, parkingBrakeOn = true))
        assertEquals(false, ParkingSignal.combine(gearPark = null, parkingBrakeOn = false))
        assertNull(ParkingSignal.combine(null, null))
    }

    @Test
    fun silenceWithoutMicrophoneIsNotCountedAsDrowsiness() {
        speech.canListen = false
        speech.answer = { "" to null }
        controller.attach()
        controller.startTrip()
        tick(30) // 30분
        assertNull(ui.latencyRatio)
        assertEquals(RiskLevel.NORMAL, ui.level)
    }

    @Test
    fun withoutShelterDataOpensNaviSearch() {
        controller.attach()
        controller.startTrip()
        tick()
        speech.answer = { "응" to 2800L }
        var guard = 0
        while (ui.phase != Phase.EN_ROUTE && guard++ < 300) tick()
        assertTrue(navi.searchOpened)
        assertNotNull(speech.spoken.firstOrNull { it.contains("내비에서 찾아") })
    }
}
