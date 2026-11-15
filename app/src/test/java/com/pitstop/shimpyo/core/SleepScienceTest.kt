package com.pitstop.shimpyo.core

import com.pitstop.shimpyo.core.talk.KoreanText
import com.pitstop.shimpyo.core.talk.KssCheckGame
import com.pitstop.shimpyo.core.talk.SleepCheckGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.pitstop.shimpyo.core.sim.SimNaviGateway
import com.pitstop.shimpyo.core.sim.SimVehicleGateway
import kotlin.random.Random

class SleepScienceTest {

    private fun eval(
        drive: Int = 10, hour: Int = 10, sleep: Double? = null, awake: Double? = null,
        kss: Int? = null, graded: Int = 0, wrong: Int = 0,
    ) = RiskEngine.evaluate(RiskEngine.Input(drive, hour, null, false, sleep, awake, kss, graded, wrong))

    @Test
    fun sleepHoursFollowCrashRiskBands() {
        // Tefft 2016: 7h+ 기준, 6~7h 1.3배 < 5~6h 1.9배 < 4~5h 4.3배 < 4h 미만 11.5배
        val scores = listOf(8.0, 6.5, 5.5, 4.5, 3.0).map { eval(sleep = it).score }
        assertEquals(scores.sorted(), scores)
        assertEquals(0, scores[0])
        assertEquals(RiskLevel.WARNING, eval(sleep = 3.0).level)          // 4시간 미만은 처음부터 경고
        assertEquals(RiskLevel.REST_ADVISED, eval(sleep = 1.5).level)     // 2시간 이하: 운전 부적합 합의
    }

    @Test
    fun kssSetsMinimumLevel() {
        assertEquals(RiskLevel.NORMAL, eval(kss = 5).level)
        assertEquals(RiskLevel.CAUTION, eval(kss = 7).level)
        assertEquals(RiskLevel.WARNING, eval(kss = 8).level)
        assertTrue("단계와 점수가 어긋나지 않는다", eval(kss = 8).score >= 50)
        assertEquals(RiskLevel.REST_ADVISED, eval(kss = 9).level)
        assertTrue(eval(kss = 8).reasons.any { it.contains("KSS 8") })
    }

    @Test
    fun hoursAwakeAndErrorsAddScore() {
        assertEquals(0, eval(awake = 10.0).score)
        assertEquals(15, eval(awake = 18.0).score)
        assertEquals(30, eval(awake = 25.0).score)
        assertEquals(0, eval(graded = 2, wrong = 2).score)  // 3문제 미만이면 판단하지 않는다
        assertEquals(15, eval(graded = 5, wrong = 3).score)
    }

    @Test
    fun parsesSleepAnswers() {
        assertEquals(5.5, KoreanText.parseHours("다섯 시간 반")!!, 1e-9)
        assertEquals(5.5, KoreanText.parseHours("5시간 30분")!!, 1e-9)
        assertEquals(7.0, KoreanText.parseHours("일곱 시간이요")!!, 1e-9)
        assertEquals(4.0, KoreanText.parseHours("네 시간 정도")!!, 1e-9)
        assertEquals(0.0, KoreanText.parseHours("한숨도 못 잤어")!!, 1e-9)
        assertNull(KoreanText.parseHours("글쎄"))
        assertEquals(7.0, KoreanText.parseHours("열한 시에 자서 여섯 시에 일어났어")!!, 1e-9)
        assertEquals(6.5, KoreanText.parseHours("12시 반에 자서 7시에 깼어")!!, 1e-9)
    }

    @Test
    fun parsesWakeTimeAndHoursAwake() {
        val a = KoreanText.parseClock("아침 7시")!!
        assertEquals(7.0, a.hour, 1e-9)
        assertEquals(16.0, KoreanText.hoursSince(a, 23.0), 1e-9)
        val b = KoreanText.parseClock("오후 두 시 반")!!
        assertEquals(14.5, b.hour, 1e-9)
        // 오전·오후 없이 “7시”: 지금이 23시면 7시(16시간)와 19시(4시간) 중 짧은 쪽
        assertEquals(4.0, KoreanText.hoursSince(KoreanText.parseClock("일곱 시")!!, 23.0), 1e-9)
        assertEquals(11.0, KoreanText.parseClock("열한 시")!!.hour, 1e-9)
    }

    @Test
    fun parsesKssAnswers() {
        assertEquals(7, KoreanText.parseKss("7"))
        assertEquals(7, KoreanText.parseKss("일곱이요"))
        assertEquals(7, KoreanText.parseKss("칠이요"))
        assertEquals(8, KoreanText.parseKss("많이 졸려"))
        assertEquals(9, KoreanText.parseKss("눈이 감겨요"))
        assertEquals(6, KoreanText.parseKss("조금 졸려요"))
        assertEquals(4, KoreanText.parseKss("괜찮아"))
        assertEquals(3, KoreanText.parseKss("안 졸려요"))
        assertEquals(3, KoreanText.parseKss("피곤하지 않아"))
        assertNull(KoreanText.parseKss("12"))
        assertNull(KoreanText.parseKss("음"))
    }

    @Test
    fun sleepCheckMovesOnWhenNoAnswer() {
        val game = SleepCheckGame({}, {}, {})
        game.nextPrompt(); game.skip()
        assertTrue(game.nextPrompt().contains("일어나셨"))
        game.skip()
        assertEquals(KssCheckGame.PROMPT, game.nextPrompt())
    }

    @Test
    fun sleepCheckCollectsAllThree() {
        var sleep: Double? = null; var wake: KoreanText.ClockAnswer? = null; var k: Int? = null
        val game = SleepCheckGame({ sleep = it }, { wake = it }, { k = it })
        assertTrue(game.nextPrompt().contains("몇 시간"))
        assertTrue(game.react("네 시간").contains("부족"))
        game.nextPrompt(); game.react("새벽 다섯 시")
        assertEquals(KssCheckGame.PROMPT, game.nextPrompt())
        game.react("8")
        assertEquals(4.0, sleep!!, 1e-9)
        assertEquals(5.0, wake!!.hour, 1e-9)
        assertEquals(8, k)
    }

    /** 질문 내용에 맞춰 대답하는 가짜 음성. */
    private class PromptSpeech(val reply: (String) -> String) : SpeechGateway {
        override val backend = Backend.SIM
        override val canListen = true
        val spoken = mutableListOf<String>()
        override fun start() = Unit
        override fun stop() = Unit
        override fun speak(text: String, onDone: () -> Unit) { spoken += text; onDone() }
        override fun listen(timeoutMs: Long, onResult: (String, Long?) -> Unit) = onResult(reply(spoken.last()), 1200L)
        override fun cancel() = Unit
    }

    @Test
    fun shortSleepAndHighKssLeadToEarlyRestOffer() {
        var now = 0L
        val clock = object : Clock {
            override fun nowMs() = now
            override fun hourOfDay() = 10
        }
        val speech = PromptSpeech { q ->
            when {
                q.contains("몇 시간 주무셨") -> "세 시간"
                q.contains("일어나셨") -> "새벽 네 시"
                q.contains("1부터 9까지") -> "8"
                else -> "응"
            }
        }
        val navi = SimNaviGateway()
        var ui = UiState()
        val c = ShimpyoController(SimVehicleGateway(), navi, speech, clock, ShimpyoConfig(timeScale = 60), Random(3)) { ui = it }
        c.attach()
        c.setShelters(ShelterDirectory.parseCsv("졸음쉼터명,위도,경도\n앞쪽,37.08,127.00"))
        navi.simulateLocation(GeoPoint(36.99, 127.0))
        navi.simulateLocation(GeoPoint(37.00, 127.0))
        c.startTrip()
        var guard = 0
        while (ui.phase != Phase.EN_ROUTE && guard++ < 30) { now += 1000; c.tick() }
        assertEquals(Phase.EN_ROUTE, ui.phase)
        assertTrue("짧게 자고 KSS 8이면 출발 직후에 쉼터를 권한다: ${ui.drivingMinutes}분", ui.drivingMinutes < 15)
        assertEquals(3.0, ui.sleepHours!!, 1e-9)
        assertEquals(8, ui.kss)
        assertTrue(speech.spoken.any { it.contains("사고 위험이 크게") })
        assertTrue(speech.spoken.any { it.contains("앞쪽 졸음쉼터") })
    }

    @Test
    fun doesNotOfferAgainRightAfterRestWhenSleepDeprived() {
        var now = 0L
        val clock = object : Clock {
            override fun nowMs() = now
            override fun hourOfDay() = 10
        }
        val speech = PromptSpeech { q ->
            when {
                q.contains("몇 시간 주무셨") -> "세 시간"
                q.contains("일어나셨") -> "일곱 시"
                q.contains("1부터 9까지") -> "5"
                else -> "응"
            }
        }
        val navi = SimNaviGateway()
        val vehicle = SimVehicleGateway()
        var ui = UiState()
        val c = ShimpyoController(vehicle, navi, speech, clock, ShimpyoConfig(timeScale = 60, restMinutes = 15, ventSeconds = 20), Random(5)) { ui = it }
        c.attach()
        c.setShelters(ShelterDirectory.parseCsv("졸음쉼터명,위도,경도\n앞쪽,37.08,127.00"))
        navi.simulateLocation(GeoPoint(36.99, 127.0))
        navi.simulateLocation(GeoPoint(37.00, 127.0))
        c.startTrip()
        var guard = 0
        while (ui.phase != Phase.EN_ROUTE && guard++ < 30) { now += 1000; c.tick() }
        assertEquals(Phase.EN_ROUTE, ui.phase)
        navi.simulateArrival()
        vehicle.setParked(true)
        now += 1000; c.tick()
        assertEquals(Phase.RESTING, ui.phase)
        guard = 0
        while (ui.phase != Phase.MONITORING && guard++ < 60) { now += 1000; c.tick() }
        assertEquals(Phase.MONITORING, ui.phase)
        vehicle.setParked(false)
        val offersBefore = speech.spoken.count { it.contains("쉬었다 갈까요") }
        repeat(30) { now += 1000; c.tick() }   // 쉬고 온 뒤 30분 주행
        assertTrue("쉬고 온 직후에는 다시 권하지 않는다", offersBefore == speech.spoken.count { it.contains("쉬었다 갈까요") })
        repeat(30) { now += 1000; c.tick() }   // 60분이 지나면 다시 권한다
        assertTrue(speech.spoken.count { it.contains("쉬었다 갈까요") } > offersBefore)
    }
}
