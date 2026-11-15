package com.pitstop.shimpyo.core

import com.pitstop.shimpyo.core.talk.KoreanText
import com.pitstop.shimpyo.core.talk.KoreanText.Intent
import com.pitstop.shimpyo.core.talk.KoreanText.TeamIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.2.1 점검에서 찾은 말 알아듣기 오류. 실제 말투 그대로 고정해 둔다. */
class VoiceParsingReviewTest {

    @Test
    fun restRequestsAreNeverReadAsDecline() {
        listOf(
            "괜찮아, 쉬었다 가자", "그만 가고 쉬자", "나중에 말고 지금 쉬자", "안 쉬면 안 되겠다",
            "네 좋아요 괜찮아요", "아니 쉬자", "쉬어야겠다", "응 들렀다 가자", "차 세워줘",
        ).forEach { assertEquals(it, Intent.YES, KoreanText.intentOf(it)) }
    }

    @Test
    fun realDeclinesStayDeclines() {
        listOf("괜찮아요", "아니 안 쉴래", "나중에 쉬자", "그냥 갈래", "계속 갈게", "쉬지 말고 그냥 가자", "응 괜찮아")
            .forEach { assertEquals(it, Intent.NO, KoreanText.intentOf(it)) }
    }

    @Test
    fun askingBackIsNotYes() {
        listOf("응?", "어? 뭐라고?", "네? 다시 말해 줘", "뭐라고요", "못 들었어")
            .forEach { assertEquals(it, Intent.UNKNOWN, KoreanText.intentOf(it)) }
        // 평범한 ‘예’는 그대로
        listOf("응", "네", "응응", "네 좋아요", "어 그래").forEach { assertEquals(it, Intent.YES, KoreanText.intentOf(it)) }
        assertEquals(Intent.CANCEL, KoreanText.intentOf("그만해"))
    }

    @Test
    fun oxNegationAndCorrections() {
        assertEquals(false, KoreanText.answerOX("안 맞아"))
        assertEquals(false, KoreanText.answerOX("맞지 않아"))
        assertEquals(true, KoreanText.answerOX("그건 틀린 말 아니야"))
        assertEquals(true, KoreanText.answerOX("엑스 아니고 오"))
        assertEquals(false, KoreanText.answerOX("오 말고 엑스"))
        assertEquals(false, KoreanText.answerOX("no"))
        assertNull(KoreanText.answerOX("맞는 것 같기도 하고 아닌 것 같기도"))
        // 원래 되던 것
        assertEquals(true, KoreanText.answerOX("오"))
        assertEquals(true, KoreanText.answerOX("맞아"))
        assertEquals(false, KoreanText.answerOX("엑스"))
        assertEquals(false, KoreanText.answerOX("아니 틀려"))
    }

    @Test
    fun teamIntentReadsWhatPeopleMean() {
        assertEquals(TeamIntent.TOGETHER, KoreanText.teamIntentOf("혼자 하기 싫어"))
        assertEquals(TeamIntent.TOGETHER, KoreanText.teamIntentOf("혼자 말고 같이 해"))
        assertEquals(TeamIntent.SOLO, KoreanText.teamIntentOf("같이 하지 말자"))
        assertEquals(TeamIntent.SOLO, KoreanText.teamIntentOf("같이 안 해"))
        assertEquals(TeamIntent.SOLO, KoreanText.teamIntentOf("혼자 할게"))
        assertEquals(TeamIntent.TOGETHER, KoreanText.teamIntentOf("같이 할게"))
    }

    @Test
    fun approximateSleepHoursUseTheNumberNotTheWordHan() {
        assertEquals(3.0, KoreanText.parseHours("한 세 시간")!!, 1e-9)
        assertEquals(4.0, KoreanText.parseHours("한 네 시간?")!!, 1e-9)
        assertEquals(3.0, KoreanText.parseHours("한 서너 시간")!!, 1e-9)
        assertEquals(1.0, KoreanText.parseHours("한두 시간")!!, 1e-9)
        assertEquals(1.0, KoreanText.parseHours("한 두 시간")!!, 1e-9)
        assertEquals(5.0, KoreanText.parseHours("대여섯 시간")!!, 1e-9)
        assertEquals(6.0, KoreanText.parseHours("거의 여섯 시간")!!, 1e-9)
        assertEquals(5.0, KoreanText.parseHours("대략 5시간")!!, 1e-9)
        assertEquals(1.0, KoreanText.parseHours("한 시간")!!, 1e-9)
        assertEquals(11.0, KoreanText.parseHours("열한 시간")!!, 1e-9)
        // “잘 못 잤어”는 0시간이 아니라 모르는 대답
        assertNull(KoreanText.parseHours("잘 못 잤어"))
        assertNull(KoreanText.parseHours("별로 못 잤어"))
        assertEquals(0.0, KoreanText.parseHours("한숨도 못 잤어")!!, 1e-9)
        assertEquals(0.0, KoreanText.parseHours("못 잤어")!!, 1e-9)
    }

    @Test
    fun wakeTimeNamedAndRelative() {
        assertEquals(KoreanText.ClockAnswer(12.0, true), KoreanText.parseClock("정오"))
        assertEquals(KoreanText.ClockAnswer(12.0, true), KoreanText.parseClock("점심때"))
        assertEquals(KoreanText.ClockAnswer(0.0, true), KoreanText.parseClock("자정"))
        assertEquals(1.0, KoreanText.hoursSince(KoreanText.parseClock("1시간 전에")!!, 15.0), 1e-9)
        assertEquals(1.0, KoreanText.hoursSince(KoreanText.parseClock("한두 시간 전")!!, 15.0), 1e-9)
        assertEquals(0.5, KoreanText.hoursSince(KoreanText.parseClock("30분 전")!!, 15.0), 1e-9)
        assertEquals(0.0, KoreanText.hoursSince(KoreanText.parseClock("방금 일어났어")!!, 15.0), 1e-9)
        // ‘오전’의 ‘전’을 “…전에”로 읽지 않는다
        assertEquals(KoreanText.ClockAnswer(7.5, true), KoreanText.parseClock("오전 7시 30분"))
        assertEquals(KoreanText.ClockAnswer(7.0, true), KoreanText.parseClock("아침 7시"))
    }
}

/** 2차 점검(0.2.1)에서 추가로 찾은 것. */
class SecondReviewTest {

    @Test
    fun arithmeticAnswerIgnoresTheEchoedQuestion() {
        assertEquals(56, KoreanText.parseAnswerNumber("7 곱하기 8은 56"))
        assertEquals(56, KoreanText.parseAnswerNumber("칠 곱하기 팔은 오십육"))
        assertEquals(15, KoreanText.parseAnswerNumber("12 더하기 3은 15요"))
        assertEquals(56, KoreanText.parseAnswerNumber("오십육"))
        assertEquals(8300, KoreanText.parseAnswerNumber("팔천 삼백 원"))
        assertEquals(7, KoreanText.parseAnswerNumber("7"))
        // 연산 말만 되풀이하고 답이 없으면 예전처럼 읽는다(앞 숫자) — 틀린 답으로 채점된다.
        assertEquals(7, KoreanText.parseAnswerNumber("7 곱하기 8은"))
    }

    @Test
    fun directionParticleFollowsFinalConsonant() {
        assertEquals("으로", KoreanText.directionParticle("판"))
        assertEquals("로", KoreanText.directionParticle("리"))
        assertEquals("로", KoreanText.directionParticle("물"))
    }

    @Test
    fun restRequestsInsideGames() {
        listOf("쉬고 싶어", "그만하고 쉬자", "졸려 죽겠어", "못 버티겠어", "안 쉬면 안 되겠다", "차 세워줘")
            .forEach { assertTrue(it, KoreanText.wantsRest(it)) }
        listOf("기차", "56", "맞아", "안 쉬어도 돼", "나중에 쉬자", "")
            .forEach { assertTrue(it, !KoreanText.wantsRest(it)) }
    }
}
