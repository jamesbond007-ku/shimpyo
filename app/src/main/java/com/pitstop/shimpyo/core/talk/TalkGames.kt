package com.pitstop.shimpyo.core.talk

import kotlin.random.Random

/**
 * 듣고·생각하고·답해야 하는 짧은 음성 대화.
 * 연구(Zhang 외 2025, Applied Ergonomics 129:104616 — 모의 야간 주행에서 음성 비서에 ‘대답하기’가 듣기·따라 하기보다
 * 졸음을 더 줄임; Large 외 2017, 디지털 비서와의 대화가 수동적 피로를 줄임)에서 능동적으로 답하는 상호작용이
 * 각성 유지에 더 효과적이었다는 점을 따라, 틀어 놓는 콘텐츠가 아니라 ‘답해야 하는 질문’만 쓴다.
 * 네트워크 없이도 동작하도록 규칙 기반으로 만들었다.
 */
interface TalkGame {
    /** 기록에 남길 이름. */
    val title: String get() = "대화"
    /** 방금 대답이 맞았는지. 정답이 없는 질문이면 null. 위험도 계산의 정답률에 쓴다. */
    val lastCorrect: Boolean? get() = null
    /**
     * [lastCorrect]를 졸음 판단의 정답률에 넣을지. 지역 퀴즈처럼 아는 것에 따라 정답률이 갈리는 놀이는 false.
     * (같이하기 모드의 팀 점수에는 그대로 쓴다.)
     */
    val graded: Boolean get() = true
    /** 다음에 말할 질문. */
    fun nextPrompt(): String
    /** 운전자 답을 받아 짧게 반응할 말. */
    fun react(answer: String): String
    /** 대답이 없어서 [react]를 부르지 않고 다음 질문으로 넘어갈 때. 순서가 있는 놀이는 여기서 단계를 넘긴다. */
    fun skip() {}
    /** 시연용: 지금 문제의 정답(운전자가 말할 문장). 정답이 없으면 null. 마이크 없는 에뮬레이터 시연에 쓴다. */
    fun demoAnswer(): String? = null
}

/** 끝말잇기. 두음법칙을 지원하고, 운전자가 틀려도 지적하지 않고 부드럽게 이어 간다. */
class WordChainGame(private val random: Random = Random.Default) : TalkGame {
    override val title = "끝말잇기"
    private val used = mutableSetOf<String>()
    private var currentWord: String = ""

    override fun nextPrompt(): String {
        if (currentWord.isEmpty()) {
            currentWord = WORDS.filter { it !in used }.random(random)
            used += currentWord
            return "끝말잇기 해요. 제가 먼저 할게요. $currentWord!"
        }
        return "${currentWord}!"
    }

    override fun react(answer: String): String {
        val words = answer.split(Regex("\\s+")).map(KoreanText::hangulOnly).filter { it.length >= 2 }
        val userWord = words.lastOrNull()
        val need = KoreanText.lastSyllable(currentWord)
        if (userWord == null || need == null) {
            return pickNext(need, prefix = "괜찮아요, 제가 이어 갈게요.")
        }
        val first = KoreanText.firstSyllable(userWord)
        val ok = first != null && first in KoreanText.allowedStarts(need) && userWord !in used
        used += userWord
        return if (ok) {
            pickNext(KoreanText.lastSyllable(userWord), prefix = "${userWord}, 좋아요!")
        } else {
            pickNext(need, prefix = "‘$need’${KoreanText.directionParticle(need.toString())} 시작하는 말이었어요. 제가 해 볼게요.")
        }
    }

    override fun demoAnswer(): String? {
        val starts = KoreanText.lastSyllable(currentWord)?.let { KoreanText.allowedStarts(it) } ?: return null
        return WORDS.firstOrNull { it !in used && KoreanText.firstSyllable(it) in starts }
    }

    private fun pickNext(syllable: Char?, prefix: String): String {
        val starts = syllable?.let { KoreanText.allowedStarts(it) } ?: emptySet()
        val candidate = WORDS.filter { it !in used && KoreanText.firstSyllable(it) in starts }.randomOrNull(random)
        return if (candidate != null) {
            used += candidate
            currentWord = candidate
            "$prefix 제 차례, $candidate."
        } else {
            currentWord = WORDS.filter { it !in used }.random(random)
            used += currentWord
            "$prefix 이건 제가 졌네요. 새로 시작할게요, $currentWord."
        }
    }

    companion object {
        /** 끝말잇기용 낱말(모두 일반 명사). */
        val WORDS = listOf(
            "기차", "차표", "표지판", "판다", "다리미", "미소", "소나무", "무지개", "개나리", "리본",
            "본보기", "기러기", "기린", "인형", "형광펜", "펜션", "선물", "물감", "감자", "자동차",
            "차고", "고구마", "마차", "차양", "양말", "말굽", "굽이", "이불", "불꽃", "꽃게",
            "게시판", "판자", "자전거", "거울", "울타리", "리듬", "음악", "악기", "기억", "억새",
            "새벽", "벽지", "지도", "도로", "로봇", "봇짐", "짐수레", "레몬", "몬순", "순두부",
            "부채", "채소", "소풍", "풍선", "선풍기", "기타", "타조", "조개", "개미", "미술",
            "술래", "내비", "비행기", "기념품", "품질", "질문", "문어", "어항", "항구", "구름",
            "나비", "비누", "누룽지", "지우개", "개구리", "리모컨", "컨테이너", "너구리", "리어카",
            "카메라", "라디오", "오리", "이야기", "기온", "온도", "도시락", "낙타", "타이어", "어묵",
            "묵주", "주차장", "장갑", "갑옷", "옷걸이", "이마", "마을", "노을", 
            "대나무", "무릎", "고속도로", "휴게소", "소금", "금붕어", "어부", "부엌", 
            "가방", "방석", "석류", "유리", "이슬", "슬리퍼", "퍼즐", "즐거움", "움집", "집게",
            "게임", "임금", "금요일", "일기", "기분", "분수", "수박", "박수", "수영", "영화",
            "화분", "분필", "필통", "통조림", "림프", "핸들", "들판", "판소리", "리코더", "더위",
            "위성", "성냥", "이정표", "표정", "정류장", "장미", "미역", "역사", "사과",
            "과자", "자두", "두부", "부산", "산책", "책상", "상자", "자석", "석탄", "탄산수",
        ).distinct()
    }
}

/**
 * 숫자 계산. 덧셈·뺄셈, 두 번 이어 계산하기, 거스름돈 계산을 섞어 낸다.
 * 정답이 계산으로 정해지므로 틀린 사실을 말할 위험이 없다.
 */
class ArithmeticGame(private val random: Random = Random.Default) : TalkGame {
    override val title = "숫자 계산"
    private var answer = 0
    private var first = true
    private var unit = ""

    override fun nextPrompt(): String {
        val lead = if (first) "숫자 계산 해요. " else ""
        first = false
        unit = ""
        return lead + when (random.nextInt(4)) {
            0 -> {
                val a = random.nextInt(11, 60)
                val b = random.nextInt(2, 10)
                answer = a + b
                "$a 더하기 $b, 얼마일까요?"
            }
            1 -> {
                val a = random.nextInt(21, 90)
                val b = random.nextInt(3, 10)
                answer = a - b
                "$a 빼기 $b, 얼마일까요?"
            }
            2 -> {
                // 이어서 계산하기: 듣는 동안 머릿속에 값을 붙잡고 있어야 한다.
                val start = random.nextInt(5, 21)
                val plus = random.nextInt(3, 10)
                val minus = random.nextInt(2, start + plus - 1).coerceAtMost(9)
                answer = start + plus - minus
                "${start}에서 시작해요. $plus 더하고, $minus 빼면?"
            }
            else -> {
                // 거스름돈: 만 원을 내고 백 원 단위 물건을 샀을 때.
                val price = random.nextInt(12, 99) * 100
                answer = 10_000 - price
                unit = "원"
                "만 원을 내고 ${price}원짜리 커피를 샀어요. 거스름돈은 얼마일까요?"
            }
        }
    }

    override var lastCorrect: Boolean? = null

    override fun demoAnswer() = "$answer"

    override fun react(answer: String): String {
        val heard = KoreanText.parseAnswerNumber(answer)
        val right = "${this.answer}$unit"
        lastCorrect = heard == this.answer
        return when {
            heard == null -> "정답은 $right."
            heard == this.answer -> "정답! 머리가 잘 돌아가네요."
            else -> "아쉬워요. 정답은 $right."
        }
    }
}

/**
 * 구구단. 2~5단으로 시작해 연달아 맞히면 6~9단으로 올라가고, 틀리면 다시 쉬운 단으로 내려온다.
 * 가끔은 거꾸로 묻는다(“7 곱하기 몇이 56일까요?”).
 */
class GuGuDanGame(private val random: Random = Random.Default) : TalkGame {
    override val title = "구구단"
    private var hard = false
    private var streak = 0
    private var answer = 0
    private var reveal = ""
    private var first = true
    private var lastPair: Pair<Int, Int>? = null

    override fun nextPrompt(): String {
        val lead = if (first) "구구단 해요. " else ""
        first = false
        var pair: Pair<Int, Int>
        do {
            val dan = if (hard) random.nextInt(6, 10) else random.nextInt(2, 6)
            pair = dan to random.nextInt(2, 10)
        } while (pair == lastPair)
        lastPair = pair
        val (a, b) = pair
        return if (hard && random.nextInt(3) == 0) {
            answer = b
            reveal = "$a 곱하기 $b, ${a * b}"
            "$lead$a 곱하기 몇이 ${a * b}일까요?"
        } else {
            answer = a * b
            reveal = "$a 곱하기 $b, ${a * b}"
            "$lead$a 곱하기 $b?"
        }
    }

    override var lastCorrect: Boolean? = null

    override fun demoAnswer() = "$answer"

    override fun react(answer: String): String {
        val heard = KoreanText.parseAnswerNumber(answer)
        lastCorrect = heard == this.answer
        return if (heard == this.answer) {
            streak++
            if (!hard && streak >= 2) {
                hard = true
                streak = 0
                "정답! 이제 높은 단으로 가 볼게요."
            } else {
                listOf("정답!", "딩동댕!", "빠르네요, 정답!").random(random)
            }
        } else {
            streak = 0
            hard = false
            "아쉬워요. $reveal."
        }
    }
}

/**
 * 초성 퀴즈. 분류와 초성을 들려주고 낱말을 맞힌다.
 * 초성은 정답 낱말에서 자동으로 뽑으므로 틀린 문제가 나올 수 없고,
 * 같은 초성의 다른 낱말을 말해도 정답으로 인정한다(분류까지 기계가 판정하지 않는다).
 */
class ChosungQuizGame(private val random: Random = Random.Default) : TalkGame {
    override val title = "초성 퀴즈"
    override var lastCorrect: Boolean? = null
    private val order = ITEMS.shuffled(random).toMutableList()
    private var current: Pair<String, String> = order.first()
    private var first = true

    override fun nextPrompt(): String {
        current = order.removeFirstOrNull() ?: ITEMS.random(random)
        val (category, word) = current
        val sounds = KoreanText.chosungOf(word).map(KoreanText::chosungName).joinToString(", ")
        val lead = if (first) "초성 퀴즈 해요. " else ""
        first = false
        return "$lead$category${if (KoreanText.hasFinalConsonant(category)) "이에요" else "예요"}. $sounds. 뭘까요?"
    }

    override fun demoAnswer() = current.second

    override fun react(answer: String): String {
        val (_, word) = current
        val target = KoreanText.chosungOf(word)
        val said = answer.split(Regex("\\s+")).map(KoreanText::hangulOnly).filter { it.isNotEmpty() }
        val hit = said.firstOrNull { it == word || KoreanText.chosungOf(it) == target }
        lastCorrect = hit != null
        return when {
            hit == word -> "정답, ${word}! 대단해요."
            hit != null -> "${hit}도 맞는 초성이에요! 제가 생각한 건 ${pastCopula(word)}."
            else -> "정답은 ${pastCopula(word)}."
        }
    }

    /** “바나나였어요 / 당근이었어요”처럼 받침에 맞춘 과거형. */
    private fun pastCopula(word: String) = word + if (KoreanText.hasFinalConsonant(word)) "이었어요" else "였어요"

    companion object {
        /** (분류, 정답). 분류는 서술이 아니라 범주라서 사실 오류 여지가 없다. */
        val ITEMS = listOf(
            "과일" to "바나나", "과일" to "복숭아", "과일" to "포도", "과일" to "수박", "과일" to "딸기",
            "동물" to "코끼리", "동물" to "호랑이", "동물" to "기린", "동물" to "고양이", "동물" to "다람쥐",
            "탈것" to "자전거", "탈것" to "비행기", "탈것" to "기차", "탈것" to "버스",
            "채소" to "당근", "채소" to "오이", "채소" to "양파", "채소" to "감자",
            "음식" to "김밥", "음식" to "떡볶이", "음식" to "비빔밥", "음식" to "라면",
            "직업" to "소방관", "직업" to "요리사", "직업" to "선생님",
            "악기" to "피아노", "악기" to "기타", "악기" to "드럼",
        )
    }
}

/**
 * 상식 O/X 퀴즈. “맞아/틀려”, “오/엑스”, “응/아니” 어느 쪽으로 답해도 된다.
 * 문장은 교과서 수준에서 논란이 없는 사실만 골랐다. 정답 뒤에 한 줄 설명을 붙인다.
 */
class OxQuizGame(private val random: Random = Random.Default) : TalkGame {
    override val title = "O/X 퀴즈"
    override var lastCorrect: Boolean? = null
    data class Item(val statement: String, val answer: Boolean, val why: String)

    private val order = ITEMS.shuffled(random).toMutableList()
    private var current: Item = order.first()
    private var first = true

    override fun nextPrompt(): String {
        current = order.removeFirstOrNull() ?: ITEMS.random(random)
        val lead = if (first) "오엑스 퀴즈예요. 맞으면 ‘맞아’, 틀리면 ‘틀려’로 답해 주세요. " else ""
        first = false
        return "$lead${current.statement}"
    }

    override fun demoAnswer() = if (current.answer) "맞아" else "틀려"

    override fun react(answer: String): String {
        val heard = KoreanText.answerOX(answer)
        val truth = if (current.answer) "맞아요" else "틀려요"
        lastCorrect = heard == current.answer
        return when (heard) {
            null -> "정답은 ${truth}. ${current.why}"
            current.answer -> "정답! ${current.why}"
            else -> "아쉬워요, ${truth}. ${current.why}"
        }
    }

    companion object {
        val ITEMS = listOf(
            Item("고래는 물고기다.", false, "고래는 새끼를 낳아 젖을 먹이는 포유류예요."),
            Item("문어의 다리는 여덟 개다.", true, "그래서 영어 이름에도 8을 뜻하는 옥토가 들어가요."),
            Item("펭귄은 북극에 산다.", false, "펭귄은 남극 등 주로 남반구에 살고, 북극에는 살지 않아요."),
            Item("거미는 곤충이다.", false, "거미는 다리가 여덟 개라 곤충이 아니에요."),
            Item("달은 스스로 빛을 낸다.", false, "달은 햇빛을 반사해서 빛나 보여요."),
            Item("얼음은 물에 뜬다.", true, "물은 얼면 부피가 늘어나 같은 부피의 물보다 가벼워져요."),
            Item("박쥐는 새다.", false, "박쥐는 하늘을 나는 포유류예요."),
            Item("타조는 날지 못하는 새다.", true, "대신 아주 빨리 달려요."),
            Item("소리는 진공에서도 전달된다.", false, "소리는 공기 같은 매질이 있어야 전해져요."),
            Item("1년은 52주보다 길다.", true, "52주는 364일이라 1년이 하루나 이틀 더 길어요."),
            Item("돌고래는 아가미로 숨을 쉰다.", false, "돌고래는 머리 위 숨구멍으로 공기를 마셔요."),
            Item("백두산은 한라산보다 높다.", true, "백두산은 약 2,744미터, 한라산은 약 1,947미터예요."),
            Item("한글은 세종대왕 때 만들어졌다.", true, "1443년에 만들어 1446년에 반포했어요."),
            Item("토마토는 식물학적으로 열매다.", true, "씨를 품은 열매라서 식물학에서는 과실로 봐요."),
        )
    }
}

/**
 * 단어 기억하기. 세 낱말을 들려주고 → 따라 말하기 → 사이에 짧은 문제 하나 → 다시 떠올리기.
 * 잠깐 붙잡아 두었다 꺼내는 작업 기억을 쓰게 해서, 멍하게 대답하는 것보다 정신을 깨운다.
 */
class MemoryGame(private val random: Random = Random.Default) : TalkGame {
    override val title = "단어 기억하기"
    override var lastCorrect: Boolean? = null

    override fun skip() {
        step++
        lastCorrect = null
    }
    private var words: List<String> = emptyList()
    private var step = 0
    private var a = 0
    private var b = 0

    override fun nextPrompt(): String {
        return when (step % 3) {
            0 -> {
                words = SETS.random(random)
                "단어 세 개를 기억해 주세요. ${words.joinToString(", ")}. 따라 말해 볼까요?"
            }
            1 -> {
                a = random.nextInt(3, 10)
                b = random.nextInt(3, 10)
                "그 사이에 하나만요. $a 더하기 $b, 얼마예요?"
            }
            else -> "아까 기억한 단어 세 개, 말해 주세요."
        }
    }

    override fun demoAnswer() = if (step % 3 == 1) "${a + b}" else words.joinToString(" ")

    override fun react(answer: String): String {
        val phase = step % 3
        step++
        lastCorrect = null
        return when (phase) {
            0 -> "좋아요, 잘 기억해 두세요."
            1 -> {
                lastCorrect = KoreanText.parseAnswerNumber(answer) == a + b
                if (lastCorrect == true) "정답이에요." else "정답은 ${a + b}."
            }
            else -> {
                val got = words.count { answer.contains(it) }
                lastCorrect = got >= 2
                when (got) {
                    3 -> "세 개 다 기억했어요! ${words.joinToString(", ")}."
                    0 -> "정답은 ${words.joinToString(", ")}. 다음엔 꼭 맞혀 봐요."
                    else -> "${got}개 맞혔어요. 정답은 ${words.joinToString(", ")}."
                }
            }
        }
    }

    companion object {
        /** 서로 헷갈리지 않는 구체적인 낱말 묶음. */
        val SETS = listOf(
            listOf("사과", "기차", "우산"), listOf("고양이", "연필", "바다"), listOf("시계", "나무", "모자"),
            listOf("자전거", "구름", "숟가락"), listOf("호랑이", "책상", "풍선"), listOf("장갑", "달력", "수박"),
            listOf("거울", "기린", "피아노"), listOf("안경", "소나무", "라면"),
        )
    }
}

/**
 * 숫자 거꾸로 말하기. 세 자리로 시작해 맞히면 한 자리씩 늘린다(최대 다섯 자리).
 * 듣고 순서를 뒤집어 말하는 데 집중이 필요해서, 짧지만 각성 효과가 크다.
 */
class ReverseDigitsGame(private val random: Random = Random.Default) : TalkGame {
    override val title = "숫자 거꾸로 말하기"
    override var lastCorrect: Boolean? = null
    private var length = 3
    private var digits: List<Int> = emptyList()
    private var first = true

    override fun nextPrompt(): String {
        // 서로 다른 숫자만 고른다. 겹치거나 앞뒤가 같은 수(예: 9 4 9)는 거꾸로 해도 같아서 의미가 없다.
        digits = (1..9).shuffled(random).take(length)
        val lead = if (first) "숫자를 거꾸로 말해 주세요. " else ""
        first = false
        return "$lead${digits.joinToString(", ")}."
    }

    override fun demoAnswer() = digits.reversed().joinToString(" ")

    override fun react(answer: String): String {
        val heard = KoreanText.parseDigits(answer)
        val expected = digits.reversed()
        val spoken = expected.joinToString(", ")
        lastCorrect = heard == expected
        return if (heard == expected) {
            val msg = if (length < 5) "정답! 이번엔 한 자리 늘려 볼게요." else "정답! 다섯 자리도 거뜬하네요."
            length = (length + 1).coerceAtMost(5)
            msg
        } else {
            length = (length - 1).coerceAtLeast(3)
            "거꾸로 하면 $spoken."
        }
    }
}

/**
 * 넌센스 퀴즈. 웃으면 잠이 깬다. 말장난이라 비슷한 대답도 정답으로 넉넉히 받아 준다.
 * 모두 오래전부터 널리 돌던 말장난이다.
 */
class NonsenseQuizGame(private val random: Random = Random.Default) : TalkGame {
    override val title = "넌센스 퀴즈"
    data class Item(val question: String, val answer: String, val accept: List<String>, val why: String)

    private val order = ITEMS.shuffled(random).toMutableList()
    private var current: Item = order.first()
    private var first = true

    override fun nextPrompt(): String {
        current = order.removeFirstOrNull() ?: ITEMS.random(random)
        val lead = if (first) "넌센스 퀴즈 하나 낼게요. " else ""
        first = false
        return "$lead${current.question}"
    }

    override fun demoAnswer() = current.answer

    override fun react(answer: String): String {
        val compact = answer.replace(" ", "")
        val ok = current.accept.any { compact.contains(it) }
        return if (ok) "딩동댕! ${current.answer}. ${current.why}" else "정답은 ${current.answer}! ${current.why}"
    }

    companion object {
        val ITEMS = listOf(
            Item("세상에서 가장 뜨거운 과일은?", "천도복숭아", listOf("천도"), "천 도라서 뜨거워요."),
            Item("왕이 넘어지면?", "킹콩", listOf("킹콩"), "킹이 콩 하고 넘어져서요."),
            Item("소가 웃으면?", "우하하", listOf("우하하"), "소는 한자로 우라서 우하하."),
            Item("미소의 반대말은?", "당기소", listOf("당기소"), "미소를 미시오로 들으면, 반대는 당기시오."),
            Item("세상에서 가장 쉬운 숫자는?", "십구만", listOf("십구만", "19만", "190000"), "쉽구만, 이라서요."),
            Item("아몬드가 죽으면?", "다이아몬드", listOf("다이아"), "영어로 죽다가 다이라서요."),
            Item("오리가 얼면?", "언덕", listOf("언덕"), "언 덕, 덕은 영어로 오리예요."),
            Item("개가 사람을 가르치면?", "개인지도", listOf("개인지도"), "개가 하는 지도라서요."),
            Item("반성문을 영어로 하면?", "글로벌", listOf("글로벌"), "글로 벌 받는 거라서요."),
            Item("다리미가 좋아하는 음식은?", "피자", listOf("피자"), "주름을 펴자, 피자."),
        )
    }
}

/**
 * 대화 고르기. 같은 놀이가 연달아 나오지 않게 섞어서 돌린다.
 * 위험도가 경고 이상이면 집중이 더 필요한 놀이(숫자 거꾸로, 단어 기억, 숫자 계산, 구구단)를 먼저 고른다.
 */
class TalkDeck(private val random: Random = Random.Default) {
    enum class Kind { WORD_CHAIN, CHOSUNG, OX, MEMORY, REVERSE_DIGITS, NONSENSE, ARITHMETIC, GUGUDAN }

    private val focused = setOf(Kind.REVERSE_DIGITS, Kind.MEMORY, Kind.ARITHMETIC, Kind.GUGUDAN)
    private var queue = ArrayDeque<Kind>()
    private var last: Kind? = null

    fun next(needFocus: Boolean): TalkGame {
        if (queue.isEmpty()) refill()
        val pick = if (needFocus) queue.firstOrNull { it in focused } ?: queue.first() else queue.first()
        queue.remove(pick)
        last = pick
        return create(pick)
    }

    /** 놀이 순서. 테스트에서 확인하려고 공개한다. */
    fun peekQueue(): List<Kind> = queue.toList()

    private fun refill() {
        var order = Kind.entries.shuffled(random)
        if (order.first() == last && order.size > 1) order = order.drop(1) + order.first()
        queue = ArrayDeque(order)
    }

    /** 놀이 하나를 새로 만든다(순서와 무관). 시연 도구에서 고른 놀이를 바로 시작할 때 쓴다. */
    fun create(kind: Kind): TalkGame = when (kind) {
        Kind.WORD_CHAIN -> WordChainGame(random)
        Kind.CHOSUNG -> ChosungQuizGame(random)
        Kind.OX -> OxQuizGame(random)
        Kind.MEMORY -> MemoryGame(random)
        Kind.REVERSE_DIGITS -> ReverseDigitsGame(random)
        Kind.NONSENSE -> NonsenseQuizGame(random)
        Kind.ARITHMETIC -> ArithmeticGame(random)
        Kind.GUGUDAN -> GuGuDanGame(random)
    }
}

/**
 * 출발 전 수면 점검. 지난 24시간 수면 시간 → 일어난 시각 → 지금 졸린 정도(KSS) 순으로 묻는다.
 * 세 질문 모두 짧게 답할 수 있어서, 그날의 대답 속도 기준값도 함께 잡힌다.
 * 대답을 못 알아들으면 그 항목은 비워 두고 다른 신호로만 판단한다.
 */
class SleepCheckGame(
    private val onSleepHours: (Double) -> Unit,
    private val onWake: (KoreanText.ClockAnswer) -> Unit,
    private val onKss: (Int) -> Unit,
) : TalkGame {
    override val title = "수면 점검"
    private var step = 0

    override fun skip() {
        step++
    }

    override fun nextPrompt(): String = when (step) {
        0 -> "지난 24시간 동안 몇 시간 주무셨어요?"
        1 -> "오늘 몇 시쯤 일어나셨어요?"
        else -> KssCheckGame.PROMPT
    }

    /** 시연용 ‘정답 말하기’: 잘 자고 일찍 일어난 평범한 날의 대답. 짧은 수면은 시연 도구의 수면 점검 버튼으로. */
    override fun demoAnswer() = when (step) {
        0 -> "일곱 시간"
        1 -> "아침 일곱 시"
        else -> "3"
    }

    override fun react(answer: String): String {
        val s = step
        step++
        return when (s) {
            0 -> {
                val h = KoreanText.parseHours(answer) ?: return "알겠어요."
                onSleepHours(h)
                when {
                    h <= 2.0 -> "지난 24시간에 2시간 이하로 주무셨다면, 수면 전문가들은 운전하지 않기를 권해요. 오늘은 제가 자주 쉬자고 할게요."
                    h < 4.0 -> "4시간이 안 되게 주무셨네요. 이럴 땐 사고 위험이 크게 높아져요. 가까운 쉼터에서 잠깐 눈을 붙이고 가요."
                    h < 5.0 -> "조금 부족하게 주무셨네요. 오늘은 제가 더 자주 살필게요."
                    h < 7.0 -> "알겠어요. 피곤해지면 제가 말을 걸 때 ‘쉬고 싶어’라고 해 주세요."
                    else -> "푹 주무셨네요."
                }
            }
            1 -> {
                KoreanText.parseClock(answer)?.let(onWake)
                "알겠어요."
            }
            else -> {
                val k = KoreanText.parseKss(answer) ?: return "알겠어요. ${KssCheckGame.REST_HINT}"
                onKss(k)
                KssCheckGame.reactionFor(k)
            }
        }
    }
}

/**
 * 카롤린스카 졸음 척도(KSS) 자가평가. 1(아주 또렷함)~9(잠과 싸우는 중) 중 지금 상태를 묻는다.
 * 수면 연구에서 널리 쓰는 척도로, 7 이상은 운전 수행 저하와 관련된다(Åkerstedt 외 2014).
 */
class KssCheckGame(
    private val onKss: (Int) -> Unit,
    /** 이번 운행에서 이미 한 번 척도를 설명했으면 짧게 묻는다(운전 중에 12초짜리 설명을 매번 듣지 않게). */
    private val brief: Boolean = false,
) : TalkGame {
    override val title = "졸림 자가평가(KSS)"

    override fun nextPrompt(): String = if (brief) BRIEF_PROMPT else PROMPT

    override fun demoAnswer() = "3"

    override fun react(answer: String): String {
        val k = KoreanText.parseKss(answer) ?: return "알겠어요. ${KssCheckGame.REST_HINT}"
        onKss(k)
        return reactionFor(k)
    }

    companion object {
        /**
         * 쉼표는 늘 듣고 있지 않다(질문한 뒤에만 듣는다). 그래서 “언제든 말해 주세요” 대신 할 수 있는 방법을 그대로 말한다.
         * 주행 중 화면 조작을 요구하지 않는다는 안전 원칙에 맞춰, 화면 버튼은 ‘차를 세운 뒤’로만 안내한다.
         */
        const val REST_HINT = "졸리면 제가 말을 걸 때 ‘쉬고 싶어’라고 해 주세요. 차를 세운 뒤에는 화면의 ‘지금 쉬기’를 눌러도 돼요."
        const val BRIEF_PROMPT = "지금 졸린 정도를 1부터 9까지 숫자로 말해 주세요. 9는 잠과 싸우는 중이에요."
        const val PROMPT = "지금 졸린 정도를 1부터 9까지 숫자로 말해 주세요. 1은 아주 또렷함, 5는 또렷하지도 졸리지도 않음, 7은 졸리지만 깨어 있기 어렵지 않음, 9는 잠과 싸우는 중이에요."

        fun reactionFor(k: Int): String = when {
            k >= 9 -> "많이 위험한 상태예요. 가까운 곳에서 꼭 쉬어 가요."
            k == 8 -> "꽤 졸리시군요. 쉴 곳을 찾아볼게요."
            k == 7 -> "졸음이 오기 시작했네요. 바깥 공기를 넣고 이야기 나눠요."
            k >= 5 -> "알겠어요. 계속 같이 살필게요."
            else -> "또렷하시네요. 좋아요."
        }
    }
}
