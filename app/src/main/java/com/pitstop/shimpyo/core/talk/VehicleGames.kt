package com.pitstop.shimpyo.core.talk

import com.pitstop.shimpyo.core.Shelter
import com.pitstop.shimpyo.core.ShelterCandidate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/*
 * 차 안에서만 할 수 있는 놀이. 내비 현재 위치와 진행 방향, 앱에 넣은 졸음쉼터 공공데이터(253곳)로 문제를 만든다.
 *
 * 문제의 정답은 모두 데이터에서 바로 나온다(시도·시군구·도로노선·주차면수, 직선거리).
 * 지역 특산물·역사처럼 데이터에 없는 내용은 확인 없이 지어낼 수 있어서 넣지 않았다.
 *
 * 이 놀이들은 그 지역을 아는지에 따라 정답률이 달라지므로 졸음 판단의 정답률에는 넣지 않는다([graded] = false).
 * 대답 속도(지연)는 다른 놀이처럼 그대로 잰다.
 */

/** 지금 지나는 곳 근처 쉼터로 만든 O/X 퀴즈. */
class RegionQuizGame internal constructor(private val items: List<Item>) : TalkGame {
    override val title = "지역 O/X"
    override val graded = false
    override var lastCorrect: Boolean? = null

    data class Item(val statement: String, val answer: Boolean, val why: String)

    private var index = -1
    private val current: Item get() = items[index.coerceIn(0, items.lastIndex)]

    override fun nextPrompt(): String {
        index = (index + 1).coerceAtMost(items.lastIndex)
        val lead = if (index == 0) "지금 지나는 곳 근처 쉼터 퀴즈예요. 맞으면 ‘맞아’, 틀리면 ‘틀려’로 답해 주세요. " else ""
        return "$lead${current.statement}"
    }

    override fun demoAnswer() = if (current.answer) "맞아" else "틀려"

    override fun react(answer: String): String {
        val heard = KoreanText.answerOX(answer)
        val truth = if (current.answer) "맞아요" else "틀려요"
        lastCorrect = heard?.let { it == current.answer }
        return when (heard) {
            null -> "정답은 ${truth}. ${current.why}"
            current.answer -> "정답! ${current.why}"
            else -> "아쉬워요, ${truth}. ${current.why}"
        }
    }

    override fun skip() {
        lastCorrect = null
    }

    /** 문제 수(대화 차례 수). */
    val size: Int get() = items.size

    companion object {
        /**
         * 노선 이름을 그대로 읽어도 되는 고속도로만 쓴다. 원본 데이터에는
         * ‘광주광역시대구선’, ‘대전라남도부순환선’처럼 지명이 잘못 바뀐 듯한 값이 있어 문제에서 뺀다.
         */
        private val CLEAN_ROUTES = setOf(
            "경부선", "서해안선", "중부내륙선", "호남선", "영동선", "통영대전선", "중앙선", "중부선",
            "남해선", "서울양양선", "평택제천선", "서울외곽선", "동해선", "청주영덕선",
        )
        private const val PARKING_LINE = 10

        private enum class Kind { PROVINCE, DISTRICT, ROUTE, PARKING }

        /**
         * [nearby](가까운 순, 같은 이름은 하나씩)로 문제를 최대 [count]개 만든다. 만들 수 없으면 null.
         * 오답 보기는 [all](전체 쉼터)에서 실제로 있는 다른 시도·시군구·노선 이름을 고른다.
         */
        fun build(nearby: List<ShelterCandidate>, all: List<Shelter>, random: Random, count: Int = 3): RegionQuizGame? {
            if (nearby.isEmpty()) return null
            val provinces = all.map { it.province }.filter { it.isNotEmpty() }.distinct()
            val items = mutableListOf<Item>()
            val usedKinds = mutableListOf<Kind>()
            var round = 0
            while (items.size < count && round < count * 3) {
                val shelter = nearby[round % nearby.size].shelter
                round++
                val kinds = applicableKinds(shelter, all).shuffled(random)
                    .sortedBy { k -> usedKinds.count { it == k } }
                for (kind in kinds) {
                    val item = makeItem(kind, shelter, all, provinces, random) ?: continue
                    if (items.any { it.statement == item.statement }) continue
                    items += item
                    usedKinds += kind
                    break
                }
            }
            return if (items.isEmpty()) null else RegionQuizGame(items)
        }

        /**
         * 같은 이름의 쉼터들. 졸음쉼터는 상·하행에 같은 이름으로 한 쌍씩 있는 경우가 많고, 둘의 주차면수나 시군구가
         * 다를 수 있다(예: 상번천 34면·7면, 삼례 완주군·익산시). “○○ 졸음쉼터는 …”이라는 문장이 어느 쪽을 가리켜도
         * 참·거짓이 같아야 하므로, 문제는 같은 이름 전체에서 값이 하나로 모일 때만 낸다.
         */
        private fun siblings(s: Shelter, all: List<Shelter>): List<Shelter> =
            all.filter { it.name == s.name }.ifEmpty { listOf(s) }

        private fun applicableKinds(s: Shelter, all: List<Shelter>): List<Kind> = buildList {
            val same = siblings(s, all)
            if (s.province.isNotEmpty() && same.all { it.province == s.province }) add(Kind.PROVINCE)
            // 시군구 이름이 쉼터 이름과 같으면(칠곡 → 칠곡군) 너무 쉬워서 뺀다.
            if (s.district.isNotEmpty() && same.all { it.district == s.district } && !s.district.startsWith(s.name) &&
                all.any { it.province == s.province && it.district.isNotEmpty() && it.district != s.district }
            ) add(Kind.DISTRICT)
            if (s.road in CLEAN_ROUTES && same.all { it.road == s.road }) add(Kind.ROUTE)
            val spaces = same.map { it.parkingSpaces }
            if (spaces.all { it != null } && (spaces.all { it!! > PARKING_LINE } || spaces.all { it!! < PARKING_LINE })) {
                add(Kind.PARKING)
            }
        }

        private fun makeItem(kind: Kind, s: Shelter, all: List<Shelter>, provinces: List<String>, random: Random): Item? {
            val name = s.spokenName
            val same = siblings(s, all)
            val where = listOf(s.province, s.district).filter { it.isNotEmpty() }.joinToString(" ")
            val road = if (s.road in CLEAN_ROUTES) ", ${s.road}" else ""
            val located = "${name}는 $where${road}에 있어요."
            val truth = random.nextBoolean()
            return when (kind) {
                Kind.PROVINCE -> {
                    val taken = same.map { it.province }.toSet()
                    val other = provinces.filter { it !in taken }.randomOrNull(random) ?: return null
                    val shown = if (truth) s.province else other
                    Item("${name}는 ${shown}에 있다.", truth, located)
                }
                Kind.DISTRICT -> {
                    val taken = all.filter { it.name == s.name }.map { it.district }.toSet()
                    val other = all.filter { it.province == s.province && it.district.isNotEmpty() && it.district !in taken }
                        .map { it.district }.distinct().randomOrNull(random) ?: return null
                    val shown = if (truth) s.district else other
                    Item("${name}는 ${s.province} ${shown}에 있다.", truth, located)
                }
                Kind.ROUTE -> {
                    val taken = same.map { it.road }.toSet()
                    val other = CLEAN_ROUTES.filter { it !in taken }.randomOrNull(random) ?: return null
                    val shown = if (truth) s.road else other
                    Item("${name}는 ${shown}에 있다.", truth, located)
                }
                Kind.PARKING -> {
                    val counts = same.mapNotNull { it.parkingSpaces }.distinct().sorted()
                    if (counts.isEmpty()) return null
                    val why = if (counts.size == 1) {
                        "${name}의 주차 면수는 ${counts.first()}면이에요."
                    } else {
                        "${name}의 주차 면수는 방향에 따라 ${counts.joinToString("면, ")}면이에요."
                    }
                    Item("${name}에는 차를 ${PARKING_LINE}대보다 많이 세울 수 있다.", counts.first() > PARKING_LINE, why)
                }
            }
        }
    }
}

/**
 * 다음 쉼터까지 거리 맞히기. 진행 방향 앞쪽 쉼터까지의 직선거리(킬로미터)를 숫자로 맞힌다.
 * 정답을 듣는 것만으로 ‘쉴 곳이 얼마나 가까운지’를 운전자가 알게 된다.
 */
class DistanceGuessGame internal constructor(private val targets: List<ShelterCandidate>) : TalkGame {
    override val title = "쉼터 거리 맞히기"
    override val graded = false
    override var lastCorrect: Boolean? = null

    private var index = -1
    private val current: ShelterCandidate get() = targets[index.coerceIn(0, targets.lastIndex)]

    /** 문제 수(대화 차례 수). */
    val size: Int get() = targets.size

    override fun nextPrompt(): String {
        index = (index + 1).coerceAtMost(targets.lastIndex)
        val name = current.shelter.spokenName
        return if (index == 0) {
            "다음 쉼터 거리 맞히기예요. 앞쪽 ${name}까지 직선거리로 몇 킬로미터쯤 될까요? 숫자로 말해 주세요."
        } else {
            "그다음 ${name}까지는 몇 킬로미터쯤 될까요?"
        }
    }

    override fun demoAnswer() = "${max(1, current.distanceKm.roundToInt())}"

    override fun react(answer: String): String {
        val actual = max(1, current.distanceKm.roundToInt())
        val guess = KoreanText.parseNumber(answer)
        val tolerance = max(2.0, actual * 0.2)
        lastCorrect = guess?.let { abs(it - actual) <= tolerance }
        val tail = if (index == 0 && actual <= 15) " 피곤하면 들렀다 가요." else ""
        return when {
            guess == null -> "정답은 약 ${actual}킬로미터예요.$tail"
            guess == actual -> "딱 맞혔어요! 약 ${actual}킬로미터예요.$tail"
            lastCorrect == true -> "거의 맞혔어요. 실제로는 약 ${actual}킬로미터예요.$tail"
            guess > actual -> "생각보다 가까워요. 약 ${actual}킬로미터예요.$tail"
            else -> "조금 더 가야 해요. 약 ${actual}킬로미터예요.$tail"
        }
    }

    override fun skip() {
        lastCorrect = null
    }

    companion object {
        fun build(ahead: List<ShelterCandidate>): DistanceGuessGame? =
            if (ahead.isEmpty()) null else DistanceGuessGame(ahead.take(2))
    }
}
