package com.pitstop.shimpyo.core.talk

/** 한글 음절 처리, 초성 추출, 음성 의도(예/아니오/취소·O/X) 판별, 한국어 숫자 읽기. */
object KoreanText {

    private const val HANGUL_BASE = 0xAC00
    private const val HANGUL_LAST = 0xD7A3
    private const val INITIAL_N = 2   // ㄴ
    private const val INITIAL_R = 5   // ㄹ
    private const val INITIAL_NG = 11 // ㅇ

    /** ㅑ ㅕ ㅖ ㅛ ㅠ ㅣ: 두음법칙에서 ㄴ·ㄹ이 ㅇ으로 바뀌는 모음 */
    private val Y_MEDIALS = setOf(2, 6, 7, 12, 17, 20)

    fun isHangulSyllable(c: Char): Boolean = c.code in HANGUL_BASE..HANGUL_LAST

    /** 문자열에서 한글 음절만 남긴다. */
    fun hangulOnly(text: String): String = text.filter(::isHangulSyllable)

    fun lastSyllable(word: String): Char? = hangulOnly(word).lastOrNull()

    fun firstSyllable(word: String): Char? = hangulOnly(word).firstOrNull()

    /**
     * 끝말잇기에서 [syllable] 다음에 시작할 수 있는 음절 집합.
     * 두음법칙: 녀·뇨·뉴·니 → 여·요·유·이, 랴·려·례·료·류·리 → 야·여·예·요·유·이,
     * 라·래·로·뢰·루·르 → 나·내·노·뇌·누·느
     */
    fun allowedStarts(syllable: Char): Set<Char> {
        if (!isHangulSyllable(syllable)) return setOf(syllable)
        val code = syllable.code - HANGUL_BASE
        val initial = code / (21 * 28)
        val medial = (code % (21 * 28)) / 28
        val final = code % 28
        val result = mutableSetOf(syllable)
        val converted = when {
            initial == INITIAL_R && medial in Y_MEDIALS -> INITIAL_NG
            initial == INITIAL_R -> INITIAL_N
            initial == INITIAL_N && medial in Y_MEDIALS -> INITIAL_NG
            else -> null
        }
        if (converted != null) {
            result += (HANGUL_BASE + (converted * 21 + medial) * 28 + final).toChar()
        }
        return result
    }

    private val CHOSUNG = charArrayOf('ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ')

    /** 초성만 뽑는다. “바나나” → “ㅂㄴㄴ”. 한글이 아닌 글자는 버린다. */
    fun chosungOf(word: String): String =
        hangulOnly(word).map { CHOSUNG[(it.code - HANGUL_BASE) / (21 * 28)] }.joinToString("")

    /** 초성 이름(소리 내어 읽는 말). 음성으로 초성 퀴즈를 낼 때 쓴다. */
    fun chosungName(c: Char): String = when (c) {
        'ㄱ' -> "기역"; 'ㄲ' -> "쌍기역"; 'ㄴ' -> "니은"; 'ㄷ' -> "디귿"; 'ㄸ' -> "쌍디귿"; 'ㄹ' -> "리을"
        'ㅁ' -> "미음"; 'ㅂ' -> "비읍"; 'ㅃ' -> "쌍비읍"; 'ㅅ' -> "시옷"; 'ㅆ' -> "쌍시옷"; 'ㅇ' -> "이응"
        'ㅈ' -> "지읒"; 'ㅉ' -> "쌍지읒"; 'ㅊ' -> "치읓"; 'ㅋ' -> "키읔"; 'ㅌ' -> "티읕"; 'ㅍ' -> "피읖"; 'ㅎ' -> "히읗"
        else -> c.toString()
    }

    /** 마지막 글자에 받침이 있는지. 한글이 아니면 받침 없음으로 본다. */
    fun hasFinalConsonant(word: String): Boolean {
        val last = word.trim().lastOrNull() ?: return false
        if (!isHangulSyllable(last)) return false
        return (last.code - HANGUL_BASE) % 28 != 0
    }

    /** 주격 조사: 받침 있으면 ‘이’, 없으면 ‘가’. */
    fun subjectParticle(word: String): String = if (hasFinalConsonant(word)) "이" else "가"

    /** 목적격 조사: 받침 있으면 ‘을’, 없으면 ‘를’. */
    fun objectParticle(word: String): String = if (hasFinalConsonant(word)) "을" else "를"

    enum class Intent { YES, NO, CANCEL, UNKNOWN }

    /** 같이하기 모드 전환 말. “같이 할게”, “함께 하자” → TOGETHER, “혼자 할게”, “같이 안 할래” → SOLO. */
    enum class TeamIntent { TOGETHER, SOLO }

    private val doPhrases = listOf("할게", "할께", "하자", "할래", "해요", "할게요", "합시다", "하기", "해")

    fun teamIntentOf(text: String): TeamIntent? {
        val t = text.replace(" ", "")
        if (t.isEmpty()) return null
        val together = t.contains("같이") || t.contains("함께") || t.contains("팀으로")
        val alone = t.contains("혼자")
        // “혼자 하기 싫어”, “혼자 말고 같이”는 같이하자는 뜻이다.
        val notAlone = listOf("혼자하기싫", "혼자는싫", "혼자싫", "혼자말고", "혼자안할", "혼자안해").any { t.contains(it) }
        val refuse = listOf("안할", "안해", "싫", "그만", "말자", "하지마", "하지말", "안하").any { t.contains(it) }
        val doing = doPhrases.any { t.contains(it) }
        return when {
            notAlone -> TeamIntent.TOGETHER
            alone -> TeamIntent.SOLO
            together && refuse -> TeamIntent.SOLO
            together && doing -> TeamIntent.TOGETHER
            else -> null
        }
    }

    // 띄어쓰기를 지운 문장에서 찾는다(“그냥 갈래”, “계속갈게”, “안 갈래”).
    private val noPhrases = listOf("아니", "싫어", "싫다", "괜찮", "됐어", "나중에", "안쉬", "그냥가", "그냥갈", "계속가", "계속갈", "안갈", "안가", "노노", "필요없", "싶지않", "싶진않")
    private val cancelPhrases = listOf("취소", "그만", "멈춰", "중지", "스톱")
    private val yesExact = setOf("응", "네", "예", "어", "웅", "엉", "넹", "콜", "오케이", "ok", "okay", "yes")
    private val yesPhrases = listOf("좋아", "그래", "가자", "쉬자", "쉴래", "갈래", "알겠", "그러자", "부탁", "쉬어 가", "쉬었다 가")

    /** 다시 말해 달라는 되묻기. “응?”, “어? 뭐라고?”를 ‘예’로 읽으면 안 된다. */
    private val askBackPhrases = listOf("뭐라고", "뭐라구", "뭐라는", "다시말", "다시한번", "못들었", "못알아", "뭐요", "뭐야", "뭐지", "네뭐")

    /** 쉬겠다는 말. 이 말이 있으면 앞에 ‘괜찮아’, ‘그만’, ‘아니’가 붙어도 쉬자는 뜻이다(“괜찮아, 쉬었다 가자”, “그만 가고 쉬자”). */
    private val restYesPhrases = listOf(
        "쉬자", "쉴래", "쉴게", "쉴께", "쉬어가", "쉬었다가", "쉬고가", "쉬다가", "쉬어야", "쉬고싶", "쉬러가", "쉬러갈",
        "들르자", "들렀다가", "들를래", "들를게", "세우자", "세워줘",
    )
    /** 쉬지 않겠다는 말. 위의 말과 같이 나오면 이쪽이 이긴다(“안 쉴래”, “나중에 쉬자”). */
    private val restNoPhrases = listOf("안쉬", "쉬지말", "쉬지않", "쉬기싫", "쉴필요없", "안쉴", "나중에쉬", "이따쉬", "다음에쉬", "안들", "안세", "싶지않", "싶진않", "싫", "필요없")
    /** 두 번 부정해서 쉬자는 말(“안 쉬면 안 되겠다”, “쉬어야겠다”). */
    private val restMustPhrases = listOf("안쉬면안", "안쉬고는안", "안쉬고는못", "쉬어야겠", "쉬어야할", "쉬어야돼", "쉬어야되")

    /**
     * 말놀이 중이라도 쉬고 싶다는 말인지(“쉬고 싶어”, “그만하고 쉬자”, “졸려서 못 버티겠어”).
     * 쉼표가 “졸리면 ‘쉬고 싶어’라고 말해 주세요”라고 안내하므로, 어느 질문의 대답에서든 알아들어야 한다.
     */
    fun wantsRest(text: String): Boolean {
        val c = text.lowercase().replace(Regex("[\\s.,!?~]"), "")
        if (c.isEmpty()) return false
        if (restMustPhrases.any { c.contains(it) }) return true
        if (listOf("못버티", "못참겠", "졸려죽", "잠와죽", "눈이감겨", "눈감겨").any { c.contains(it) }) return true
        return restYesPhrases.any { c.contains(it) } && restNoPhrases.none { c.contains(it) }
    }

    /**
     * 계산·구구단 대답의 숫자. 문제를 되풀이하며 답하는 경우(“7 곱하기 8은 56”, “칠 곱하기 팔은 오십육”)
     * 앞의 7을 답으로 읽지 않도록, 연산 말 뒤의 ‘은/는/=’ 다음을 대답으로 본다.
     */
    fun parseAnswerNumber(text: String): Int? {
        var t = text
        val op = Regex("(곱하기|더하기|빼기|[x×*+])").findAll(t).lastOrNull()
        if (op != null) {
            val tail = t.substring(op.range.last + 1)
            Regex("(은|는|=|이면|면|이야|야)\\s*").find(tail)?.let { m ->
                val after = tail.substring(m.range.last + 1)
                if (parseNumber(after) != null) t = after
            }
        }
        return parseNumber(t)
    }

    /** 방향 조사: 받침이 없거나 ㄹ받침이면 ‘로’, 그 밖의 받침이면 ‘으로’(“‘판’으로”, “‘리’로”, “‘물’로”). */
    fun directionParticle(word: String): String {
        val last = word.trim().lastOrNull() ?: return "로"
        if (!isHangulSyllable(last)) return "로"
        val fin = (last.code - HANGUL_BASE) % 28
        return if (fin == 0 || fin == 8) "로" else "으로"
    }

    fun intentOf(text: String): Intent {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return Intent.UNKNOWN
        val compact = t.replace(Regex("[\\s.,!?~]"), "")
        val firstWord = t.split(Regex("[\\s.,!?~]+")).firstOrNull { it.isNotEmpty() }.orEmpty()
        // 되묻기는 대답이 아니다. 다시 묻게 UNKNOWN으로 둔다.
        if (askBackPhrases.any { compact.contains(it) }) return Intent.UNKNOWN
        if (t.endsWith("?") && compact.length <= 2 && compact in yesExact) return Intent.UNKNOWN
        // 쉬자는 말을 먼저 본다. 졸린 운전자가 쉬겠다고 한 것을 거절로 읽는 것이 가장 위험한 실수다.
        if (restMustPhrases.any { compact.contains(it) }) return Intent.YES
        val restYes = restYesPhrases.any { compact.contains(it) }
        val restNo = restNoPhrases.any { compact.contains(it) } && !compact.contains("말고지금")
        if (restYes && !restNo) return Intent.YES
        if (cancelPhrases.any { t.contains(it) }) return Intent.CANCEL
        // “네 좋아요, 괜찮아요”: 예로 시작해서 좋다고 했으면 ‘괜찮아요’는 거절이 아니다.
        val yesLead = firstWord in yesExact || yesExact.any { y -> firstWord.length <= y.length * 3 && firstWord.chunked(y.length).all { it == y } }
        if (yesLead && (compact.contains("좋아") || compact.contains("그래")) && !restNo) return Intent.YES
        if (noPhrases.any { compact.contains(it) }) return Intent.NO
        if (compact in yesExact) return Intent.YES
        // “응응”, “네네”, “네 좋아요”처럼 대답 말이 한 단어로 맨 앞에 올 때만. (“어디야?”, “어떻게?”는 대답이 아니다)
        if (yesLead) return Intent.YES
        if (yesPhrases.any { t.contains(it) }) return Intent.YES
        return Intent.UNKNOWN
    }

    /**
     * O/X 퀴즈 대답 판별. 맞다 → true, 틀리다 → false, 알 수 없으면 null.
     * “오/엑스”, “맞아/틀려”, “응/아니” 모두 받는다. 부정 표현을 먼저 본다(“맞지 않아”, “아니 틀려”).
     */
    fun answerOX(text: String): Boolean? {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return null
        val compact = t.replace(Regex("[\\s.,!?~]"), "")
        // 망설이는 대답(“맞는 것 같기도 하고 아닌 것 같기도”)은 채점하지 않는다.
        if (compact.contains("같기도")) return null
        // 두 번 부정: “틀린 말 아니야”, “거짓 아니야” → 맞다.
        if (Regex("(틀린|틀렸|틀리|거짓)[가-힣]{0,3}아니").containsMatchIn(compact)) return true
        // 고쳐 말하기: “엑스 아니고 오” → 맞다, “오 말고 엑스” → 틀리다. 뒤에 말한 쪽이 대답이다.
        if (Regex("(엑스|x)(아니|말고)").containsMatchIn(compact)) return true
        if (Regex("(^오|^o|동그라미)(아니|말고)").containsMatchIn(compact)) return false
        // “안 맞아”, “맞지 않아”, “맞는 거 아니야”
        if (listOf("안맞", "맞지않", "맞진않", "맞는거아니", "맞는게아니").any { compact.contains(it) }) return false
        if (compact == "no" || compact == "nope") return false
        if (compact == "yes") return true
        val falseWords = listOf("엑스", "틀려", "틀렸", "틀린", "틀리", "거짓", "땡", "아니", "않", "아냐", "x")
        val trueWords = listOf("오", "맞", "동그라미", "참", "그렇", "o")
        if (falseWords.any { compact.contains(it) }) return false
        if (compact == "오" || compact.startsWith("오오") || compact == "o") return true
        if (trueWords.drop(1).any { compact.contains(it) }) return true
        // “응 그런 것 같아”, “네 그럴걸요”처럼 긍정어로 시작하면 맞다로 본다(부정어는 위에서 이미 걸렀다).
        if (compact.contains("그런") || compact.contains("그럴") || listOf("응", "네", "예").any { compact.startsWith(it) }) return true
        return when (intentOf(t)) {
            Intent.YES -> true
            Intent.NO -> false
            else -> null
        }
    }

    private val nativeDigitWords = listOf("다섯" to 5, "여섯" to 6, "일곱" to 7, "여덟" to 8, "아홉" to 9, "하나" to 1, "둘" to 2, "셋" to 3, "넷" to 4, "공" to 0, "영" to 0)
    private val sinoDigitSyllables = mapOf('일' to 1, '이' to 2, '삼' to 3, '사' to 4, '오' to 5, '육' to 6, '륙' to 6, '칠' to 7, '팔' to 8, '구' to 9)

    /**
     * 한 자리 숫자 나열을 읽는다. “2 7 4”, “274”, “이 칠 사”, “둘 일곱 넷” → [2, 7, 4].
     * 거꾸로 말하기처럼 숫자를 하나씩 부르는 대답에 쓴다.
     */
    fun parseDigits(text: String): List<Int> {
        val ascii = text.filter { it.isDigit() }
        if (ascii.isNotEmpty()) return ascii.map { it - '0' }
        val t = text.replace(Regex("[\\s.,!?~]"), "").replace(Regex("(이에요|이요|예요|에요|요|입니다)$"), "")
        val out = mutableListOf<Int>()
        var i = 0
        while (i < t.length) {
            val native = nativeDigitWords.firstOrNull { (w, _) -> t.startsWith(w, i) }
            if (native != null) {
                out += native.second
                i += native.first.length
                continue
            }
            sinoDigitSyllables[t[i]]?.let { out += it }
            i++
        }
        return out
    }

    /**
     * 수면 시간 대답 읽기. “다섯 시간 반”, “5시간 30분”, “여섯 시간”, “6.5” → 5.5, 5.5, 6.0, 6.5.
     * “못 잤어”, “한숨도 못 잤어”는 0으로 본다. 0~24 밖이거나 읽을 수 없으면 null.
     */
    /**
     * 어림 표현을 숫자 하나로 바꾼다. 범위는 낮은 쪽을 고른다(덜 잤다고 보는 쪽이 졸음 판단에서 안전하다).
     * “두세”→두, “서너”→세, “너댓·네다섯”→네, “대여섯”→다섯, “예닐곱”→여섯, “일고여덟”→일곱, “한두”→한.
     * 그리고 ‘대략’을 뜻하는 앞의 ‘한’을 지운다(“한 세 시간”→세 시간). “한 시간”의 ‘한’은 그대로 둔다.
     */
    private fun normalizeApprox(raw: String): String {
        // “한두 시간”은 1~2시간 → 1. 띄어 쓴 “한 두 시간”도 같다.
        var s = raw.replace(Regex("한\\s?두(?=\\s?시)"), "한")
        // 말머리의 어림말: “한 세 시간”, “대략 5시간”, “거의 여섯 시간” → 뒤의 숫자만 남긴다. “열한 시간”의 ‘한’은 건드리지 않는다.
        s = s.replace(
            Regex("(^|[\\s,])(대략|약|거의|한)\\s*(?=(두|세|서|네|너|다섯|대여|대엿|여섯|예닐|일곱|일고|여덟|아홉|열|\\d))"),
            "$1",
        )
        s = s.replace(" ", "")
        listOf(
            "일고여덟" to "일곱", "일곱여덟" to "일곱", "예닐곱" to "여섯", "여섯일곱" to "여섯",
            "대여섯" to "다섯", "대엿" to "다섯", "다섯여섯" to "다섯", "너댓" to "네", "너덧" to "네", "네다섯" to "네",
            "서너" to "세", "세네" to "세", "두세" to "두", "한두" to "한",
        ).forEach { (from, to) -> s = s.replace(from, to) }
        return s
    }

    fun parseHours(text: String): Double? {
        val t = normalizeApprox(text)
        if (t.contains("한숨도") || t.contains("밤샜") || t.contains("밤새웠") || t.contains("밤을새")) return 0.0
        // “잘 못 잤어”, “별로 못 잤어”는 몇 시간인지 모르는 대답이다(0시간으로 읽으면 바로 ‘운전 부적합’이 된다).
        if (Regex("(잘|별로|많이|조금|좀|푹)(못|안)잤").containsMatchIn(t) && t.none { it.isDigit() } && !t.contains("시간")) return null
        // “열한 시에 자서 여섯 시에 일어났어”처럼 잔 시각과 일어난 시각으로 답한 경우
        if (!t.contains("시간") && t.split("시").size >= 3) {
            val parts = t.split("시")
            val slept = hourAtEnd(parts[0])
            val woke = hourAtEnd(parts[1])
            if (slept != null && woke != null) {
                var d = (((woke - slept) % 12) + 12) % 12.0
                if (parts[1].startsWith("반")) d -= 0.5
                if (parts[2].startsWith("반")) d += 0.5
                return d
            }
        }
        Regex("(\\d+(?:\\.\\d+)?)").find(t)?.let { m ->
            var v = m.value.toDouble()
            if (t.contains("반")) v += 0.5
            Regex("(\\d+)분").find(t.substring(m.range.last + 1))?.let { v += it.groupValues[1].toDouble() / 60.0 }
            return v.takeIf { it in 0.0..24.0 }
        }
        val hourPart = t.substringBefore("시간")
        val base = parseNumber(hourPart) ?: return if (t.contains("못잤") || t.contains("안잤")) 0.0 else null
        var v = base.toDouble()
        val after = t.substringAfter("시간", "")
        if (after.startsWith("반") || (!t.contains("시간") && t.contains("반"))) v += 0.5
        else parseNumber(after.substringBefore("분"))?.takeIf { after.contains("분") }?.let { v += it / 60.0 }
        return v.takeIf { it in 0.0..24.0 }
    }

    /** “…아침 일곱”, “…11”처럼 문자열 끝에 붙은 시(時) 숫자를 읽는다. */
    private fun hourAtEnd(segment: String): Int? =
        Regex("\\d+").findAll(segment).lastOrNull()?.value?.toIntOrNull()
            ?: parseNative(segment.takeLast(4))
            ?: parseSino(segment.takeLast(2))

    /** 일어난 시각 대답. hour는 0~23(오전·오후 표시가 있을 때) 또는 1~12(없을 때), [explicit]는 오전/오후 표시 여부. */
    /** [hoursAgo]가 있으면 “두 시간 전에”, “방금”처럼 지금으로부터 얼마 전인지로 답한 것이다(hour는 쓰지 않는다). */
    data class ClockAnswer(val hour: Double, val explicit: Boolean, val hoursAgo: Double? = null)

    /** “아침 7시”, “오후 2시 반”, “열한 시”, “6시 30분”, “정오”, “두 시간 전” → 일어난 시각. 읽을 수 없으면 null. */
    fun parseClock(text: String): ClockAnswer? {
        val t = normalizeApprox(text)
        // 지금 기준으로 답한 경우: “방금”, “아까”, “한 시간 전에”, “30분 전”.
        if (t.contains("방금") || t.contains("이제막") || t.startsWith("막")) return ClockAnswer(0.0, true, hoursAgo = 0.0)
        // ‘오전’의 ‘전’과 헷갈리지 않게 “…시간 전”, “…분 전” 꼴만 본다.
        // “세 시간 됐어”, “두 시간밖에 안 됐어”처럼 ‘전’ 없이 지난 시간으로 답한 것도 같다(3시에 일어났다는 뜻이 아니다).
        if (Regex("(시간|분)(반)?(쯤|정도|가량|좀)?(전|됐|됬|되었|지났|밖에)").containsMatchIn(t)) {
            val hoursPart = Regex("(.*?)시간").find(t)?.groupValues?.get(1)
            // 숫자는 ‘시간’ 바로 앞의 것을 읽는다(“일어난 지 세 시간”의 ‘일’을 1로 읽지 않게).
            val h = hoursPart?.let { hourAtEnd(it) }
            val minPart = Regex("(\\d+|[가-힣]+)분").find(t.substringAfter("시간", t))?.groupValues?.get(1)
            val m = minPart?.let { parseNumber(it) }
            if (h != null || m != null) {
                var ago = (h ?: 0).toDouble() + (m ?: 0) / 60.0
                if (h != null && t.substringAfter("시간").startsWith("반")) ago += 0.5
                if (ago in 0.0..24.0) return ClockAnswer(0.0, true, hoursAgo = ago)
            }
        }
        // 이름으로 부르는 시각. ‘정오’의 ‘오’를 5로 읽지 않게 숫자보다 먼저 본다.
        if (t.contains("정오") || (t.contains("점심") && !t.contains("시"))) return ClockAnswer(12.0, true)
        if (t.contains("자정")) return ClockAnswer(0.0, true)
        val beforeSi = if (t.contains("시")) t.substringBefore("시") else t
        val h = hourAtEnd(beforeSi) ?: return null
        if (h !in 0..24) return null
        var hour = h.toDouble()
        val after = t.substringAfter("시", "")
        if (after.startsWith("반")) hour += 0.5
        else Regex("^(\\d+)분").find(after)?.let { hour += it.groupValues[1].toDouble() / 60.0 }
        // 시각 바로 앞의 말을 본다(“어젯밤 늦게 자서 아침 9시에” → 아침).
        val near = beforeSi.takeLast(6)
        val amWords = listOf("오전", "아침", "새벽")
        val pmWords = listOf("오후", "저녁", "밤", "낮")
        var am = amWords.any { near.contains(it) }
        var pm = !am && pmWords.any { near.contains(it) }
        if (!am && !pm) {
            am = amWords.any { t.contains(it) }
            pm = !am && pmWords.any { t.contains(it) }
        }
        // 자정: ‘밤 12시’, ‘새벽 12시’는 0시.
        if (h == 12 && (near.contains("밤") || near.contains("새벽"))) return ClockAnswer(hour - 12, true)
        return when {
            pm && h < 12 -> ClockAnswer(hour + 12, true)
            pm || am -> ClockAnswer(hour % 24, true)
            h >= 13 -> ClockAnswer(hour, true)
            else -> ClockAnswer(hour, false)
        }
    }

    /**
     * 일어난 시각과 지금 시각으로 깨어 있은 시간(0~24)을 구한다.
     * 오전·오후를 말하지 않았으면 두 가지 해석 중 더 짧은 쪽을 고른다(위험을 부풀리지 않게).
     */
    fun hoursSince(wake: ClockAnswer, nowHour: Double): Double {
        wake.hoursAgo?.let { return it }
        fun since(h: Double) = ((nowHour - h) % 24 + 24) % 24
        return if (wake.explicit) since(wake.hour) else minOf(since(wake.hour % 12), since(wake.hour % 12 + 12))
    }

    /**
     * KSS(카롤린스카 졸음 척도) 대답. 숫자 1~9를 우선 읽고, 숫자가 없으면 말로 한 표현을 가까운 단계로 옮긴다.
     * 말 표현 대응은 척도의 설명문(5 보통, 7 졸리지만 버틸 만함, 9 잠과 싸우는 중)에 맞췄다.
     */
    fun parseKss(text: String): Int? {
        val t = text.replace(" ", "")
        // 숫자를 먼저 찾되, “많이 졸려”의 ‘이’를 2로 읽지 않도록 말 표현이 있으면 그쪽을 먼저 본다.
        val digit = Regex("\\d+").find(t)?.value?.toIntOrNull()
        if (digit != null) return digit.takeIf { it in 1..9 }
        val worded = kssFromWords(t)
        if (worded != null) return worded
        return parseNumber(t)?.takeIf { it in 1..9 }
    }

    private fun kssFromWords(t: String): Int? {
        // “안 졸려”, “피곤하지 않아”처럼 부정하면 졸리지 않은 쪽(3, 또렷함)으로 본다.
        val negated = listOf("안졸", "안피곤", "졸리지않", "졸리진않", "피곤하지않", "피곤하진않", "하나도안", "전혀안")
            .any { t.contains(it) }
        if (negated) return 3
        return when {
            listOf("감겨", "감길", "못참", "못버티", "싸우", "쏟아").any { t.contains(it) } -> 9
            listOf("많이졸", "너무졸", "엄청졸", "진짜졸", "되게졸", "졸려죽").any { t.contains(it) } -> 8
            listOf("조금졸", "약간졸", "살짝졸", "좀졸").any { t.contains(it) } -> 6
            t.contains("졸려") || t.contains("졸리") || t.contains("피곤") -> 7
            listOf("쌩쌩", "말짱", "또렷", "개운").any { t.contains(it) } -> 2
            listOf("괜찮", "보통", "그냥그래", "멀쩡").any { t.contains(it) } -> 4
            else -> null
        }
    }

    private val sinoDigits = mapOf('일' to 1, '이' to 2, '삼' to 3, '사' to 4, '오' to 5, '육' to 6, '칠' to 7, '팔' to 8, '구' to 9)
    private val nativeTens = listOf("아흔" to 90, "여든" to 80, "일흔" to 70, "예순" to 60, "쉰" to 50, "마흔" to 40, "서른" to 30, "스물" to 20, "스무" to 20, "열" to 10)
    private val nativeOnes = listOf("다섯" to 5, "여섯" to 6, "일곱" to 7, "여덟" to 8, "아홉" to 9, "하나" to 1, "한" to 1, "둘" to 2, "두" to 2, "셋" to 3, "세" to 3, "넷" to 4, "네" to 4)

    /**
     * 음성 인식 결과에서 숫자를 읽는다. “15”, “십오”, “열다섯” 모두 15.
     * 거스름돈처럼 큰 수도 읽는다: “3,500”, “삼천오백 원”, “만 이천” → 3500, 3500, 12000.
     * 읽을 수 없으면 null.
     */
    fun parseNumber(text: String): Int? {
        val digits = text.replace(Regex("(?<=\\d)[,\\s](?=\\d{3})"), "")
        Regex("\\d+").find(digits)?.let { return it.value.toIntOrNull() }
        val t = text.replace(" ", "")
        // “일곱이요”처럼 고유어 숫자 뒤에 ‘이’가 붙으면 한자어 ‘이(2)’로 잘못 읽힐 수 있어서,
        // 고유어 숫자가 먼저 나오면 고유어로 읽는다.
        val nativeIdx = (nativeTens + nativeOnes).filter { it.first.length >= 2 || it.first == "열" }
            .map { t.indexOf(it.first) }.filter { it >= 0 }.minOrNull()
        val sinoIdx = t.indexOfFirst { it in sinoDigits || it in sinoUnits || it == '만' || it == '영' || it == '공' }
        if (nativeIdx != null && (sinoIdx < 0 || nativeIdx <= sinoIdx)) parseNative(t)?.let { return it }
        parseSino(t)?.let { return it }
        return parseNative(t)
    }

    private val sinoUnits = mapOf('십' to 10, '백' to 100, '천' to 1000)

    private fun parseSino(t: String): Int? {
        val start = t.indexOfFirst { it in sinoDigits || it in sinoUnits || it == '만' || it == '영' || it == '공' }
        if (start < 0) return null
        var total = 0     // 만 단위 이상
        var section = 0   // 만 아래 (천·백·십)
        var current = 0   // 아직 단위가 붙지 않은 한 자리
        var matched = false
        var i = start
        while (i < t.length) {
            val c = t[i]
            when {
                c == '영' || c == '공' -> matched = true
                c in sinoDigits -> {
                    // 단위 없이 숫자가 연달아 오면(“칠이요”, “오십육이요”) 뒤의 것은 조사 ‘이’ 등으로 보고 멈춘다.
                    if (current != 0) break
                    current = sinoDigits.getValue(c); matched = true
                }
                c in sinoUnits -> { section += (if (current == 0) 1 else current) * sinoUnits.getValue(c); current = 0; matched = true }
                c == '만' -> {
                    val block = section + current
                    total += (if (block == 0) 1 else block) * 10_000
                    section = 0; current = 0; matched = true
                }
                else -> break
            }
            i++
        }
        return if (matched) total + section + current else null
    }

    private fun parseNative(t: String): Int? {
        var rest = t
        var value = 0
        var matched = false
        for ((word, v) in nativeTens) {
            val idx = rest.indexOf(word)
            if (idx >= 0) {
                value += v
                rest = rest.substring(idx + word.length)
                matched = true
                break
            }
        }
        for ((word, v) in nativeOnes) {
            if (rest.startsWith(word) || (!matched && rest.contains(word))) {
                value += v
                matched = true
                break
            }
        }
        return if (matched) value else null
    }
}
