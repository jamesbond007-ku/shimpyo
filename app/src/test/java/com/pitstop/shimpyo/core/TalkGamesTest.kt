package com.pitstop.shimpyo.core

import com.pitstop.shimpyo.core.talk.ArithmeticGame
import com.pitstop.shimpyo.core.talk.ChosungQuizGame
import com.pitstop.shimpyo.core.talk.GuGuDanGame
import com.pitstop.shimpyo.core.talk.KoreanText
import com.pitstop.shimpyo.core.talk.MemoryGame
import com.pitstop.shimpyo.core.talk.NonsenseQuizGame
import com.pitstop.shimpyo.core.talk.OxQuizGame
import com.pitstop.shimpyo.core.talk.ReverseDigitsGame
import com.pitstop.shimpyo.core.talk.TalkDeck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TalkGamesTest {

    @Test
    fun chosungExtraction() {
        assertEquals("ㅂㄴㄴ", KoreanText.chosungOf("바나나"))
        assertEquals("ㄸㅂㅇ", KoreanText.chosungOf("떡볶이"))
        assertEquals("ㄱㄹ", KoreanText.chosungOf("기린!"))
        assertEquals("쌍디귿", KoreanText.chosungName('ㄸ'))
    }

    @Test
    fun oxAnswers() {
        assertEquals(true, KoreanText.answerOX("맞아"))
        assertEquals(true, KoreanText.answerOX("오"))
        assertEquals(true, KoreanText.answerOX("응 그런 것 같아"))
        assertEquals(false, KoreanText.answerOX("틀려"))
        assertEquals(false, KoreanText.answerOX("엑스"))
        assertEquals(false, KoreanText.answerOX("아니 맞지 않아"))
        assertEquals(false, KoreanText.answerOX("땡"))
        assertNull(KoreanText.answerOX("글쎄"))
    }

    @Test
    fun digitSequences() {
        assertEquals(listOf(2, 7, 4), KoreanText.parseDigits("2 7 4"))
        assertEquals(listOf(2, 7, 4), KoreanText.parseDigits("274"))
        assertEquals(listOf(2, 7, 4), KoreanText.parseDigits("이 칠 사"))
        assertEquals(listOf(2, 7, 4), KoreanText.parseDigits("둘 일곱 넷"))
        assertEquals(listOf(9, 1, 5), KoreanText.parseDigits("구, 일, 오"))
    }

    @Test
    fun chosungQuizAcceptsExactAndSameInitials() {
        val game = ChosungQuizGame(Random(3))
        val prompt = game.nextPrompt()
        assertTrue(prompt, prompt.startsWith("초성 퀴즈 해요."))
        assertFalse(prompt, prompt.contains("채소이에요") || prompt.contains("악기이에요"))
        // 프롬프트에서 분류를 읽고 같은 분류·초성의 정답을 찾는다.
        val answer = ChosungQuizGame.ITEMS.first { (cat, word) ->
            prompt.contains(cat) && prompt.contains(KoreanText.chosungOf(word).map(KoreanText::chosungName).joinToString(", "))
        }.second
        assertTrue(game.react("음 $answer").startsWith("정답"))
    }

    @Test
    fun chosungItemsAreUnambiguousWithinCategory() {
        // 같은 분류 안에 초성이 같은 정답이 둘이면 운전자가 헷갈린다.
        val keys = ChosungQuizGame.ITEMS.map { (cat, word) -> cat + KoreanText.chosungOf(word) }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun oxQuizGradesBothWays() {
        val game = OxQuizGame(Random(5))
        val prompt = game.nextPrompt()
        val item = OxQuizGame.ITEMS.first { prompt.endsWith(it.statement) }
        val right = if (item.answer) "맞아" else "틀려"
        assertTrue(game.react(right).startsWith("정답"))
        val p2 = game.nextPrompt()
        val item2 = OxQuizGame.ITEMS.first { p2.endsWith(it.statement) }
        val wrong2 = if (item2.answer) "틀려" else "맞아"
        assertTrue(game.react(wrong2).startsWith("아쉬워요"))
    }

    @Test
    fun memoryGameRunsThreeSteps() {
        val game = MemoryGame(Random(7))
        val p1 = game.nextPrompt()
        val words = MemoryGame.SETS.first { set -> set.all { p1.contains(it) } }
        game.react(words.joinToString(" "))
        val p2 = game.nextPrompt()
        val nums = Regex("\\d+").findAll(p2).map { it.value.toInt() }.toList()
        assertEquals("정답이에요.", game.react("${nums.sum()}"))
        assertTrue(game.nextPrompt().contains("기억한 단어"))
        assertTrue(game.react("${words[0]} ${words[1]} ${words[2]}").startsWith("세 개 다"))
    }

    @Test
    fun reverseDigitsGrowsOnSuccess() {
        val game = ReverseDigitsGame(Random(11))
        val p = game.nextPrompt()
        val digits = Regex("\\d").findAll(p).map { it.value.toInt() }.toList()
        assertEquals(3, digits.size)
        assertTrue(game.react(digits.reversed().joinToString(" ")).startsWith("정답"))
        val p2 = game.nextPrompt()
        assertEquals(4, Regex("\\d").findAll(p2).count())
        assertTrue(game.react("모르겠어").startsWith("거꾸로 하면"))
    }

    @Test
    fun nonsenseAcceptsLooseAnswers() {
        val game = NonsenseQuizGame(Random(2))
        val p = game.nextPrompt()
        val item = NonsenseQuizGame.ITEMS.first { p.endsWith(it.question) }
        assertTrue(game.react("${item.accept.first()} 아니야?").startsWith("딩동댕"))
    }

    @Test
    fun deckCyclesAllGamesWithoutImmediateRepeat() {
        val deck = TalkDeck(Random(1))
        val titles = (1..24).map { deck.next(needFocus = false).title }
        assertEquals(8, titles.take(8).toSet().size)
        titles.zipWithNext().forEach { (a, b) -> assertTrue("$a 연속", a != b) }
    }

    @Test
    fun deckPrefersFocusGamesWhenWarning() {
        val deck = TalkDeck(Random(4))
        val title = deck.next(needFocus = true).title
        assertTrue(title, title in setOf("숫자 거꾸로 말하기", "단어 기억하기", "숫자 계산", "구구단"))
    }

    @Test
    fun parsesLargeNumbers() {
        assertEquals(3500, KoreanText.parseNumber("3,500원"))
        assertEquals(3500, KoreanText.parseNumber("삼천오백 원이요"))
        assertEquals(12000, KoreanText.parseNumber("만 이천"))
        assertEquals(56, KoreanText.parseNumber("오십육"))
        assertEquals(56, KoreanText.parseNumber("오십육이요"))
        assertEquals(22, KoreanText.parseNumber("이십이"))
        assertEquals(12, KoreanText.parseNumber("십이요"))
        assertEquals(15, KoreanText.parseNumber("열다섯"))
    }

    @Test
    fun guguDanChecksAnswersAndLevelsUp() {
        val game = GuGuDanGame(Random(9))
        var leveledUp = false
        repeat(6) {
            val p = game.nextPrompt()
            val nums = Regex("\\d+").findAll(p).map { it.value.toInt() }.toList()
            val expected = if (p.contains("몇")) nums[1] / nums[0] else nums[0] * nums[1]
            val reply = game.react("$expected")
            assertTrue(reply, reply.contains("정답") || reply.contains("딩동댕"))
            if (reply.contains("높은 단")) leveledUp = true
        }
        assertTrue(leveledUp)
        game.nextPrompt()
        assertTrue(game.react("모르겠어").startsWith("아쉬워요"))
    }

    @Test
    fun arithmeticAnswersAreComputed() {
        val game = ArithmeticGame(Random(13))
        repeat(12) {
            val p = game.nextPrompt()
            val nums = Regex("\\d+").findAll(p).map { it.value.toInt() }.toList()
            val expected = when {
                p.contains("거스름돈") -> 10_000 - nums[0]
                p.contains("시작해요") -> nums[0] + nums[1] - nums[2]
                p.contains("더하기") -> nums[0] + nums[1]
                else -> nums[0] - nums[1]
            }
            assertTrue(p, expected >= 0)
            assertTrue(p, game.react("$expected").startsWith("정답"))
        }
    }
}
