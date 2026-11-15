package com.pitstop.shimpyo.core

import kotlin.math.roundToInt

/**
 * 졸음 위험도 계산기.
 *
 * 카메라 없이 쓸 수 있는 신호를 점수로 합산한다.
 *  [운행 상황]
 *  1. 연속 주행 시간: 도로공사의 “2시간마다 휴식” 권고를 기준으로 구간을 나눈다.
 *  2. 시간대: 자정~새벽, 식후 오후, 늦은 밤에 가산.
 *  [수면의학 근거 신호]
 *  3. 지난 24시간 수면 시간: Tefft(2016, AAA 교통안전재단) — 7시간 이상 잔 운전자 대비 사고율
 *     6~7시간 1.3배, 5~6시간 1.9배, 4~5시간 4.3배, 4시간 미만 11.5배. 점수는 이 비율의 로그에 대략 비례하게 잡았다.
 *     (이 연구는 자정~오전 6시 사고 자료가 없어 새벽 위험은 오히려 덜 반영됐을 수 있다.)
 *     또 2시간 이하로 잤다면 “운전하기에 적합하지 않다”는 전문가 합의(Czeisler 외 2016)를 따른다.
 *  4. 깨어 있은 시간: Williamson & Feyer(2000) — 17~19시간 깨어 있으면 일부 수행 검사에서
 *     혈중알코올농도 0.05%와 같거나 나빴고, 더 길어지면 0.1% 수준까지 떨어졌다.
 *  5. 카롤린스카 졸음 척도(KSS, 1~9) 자가평가: Åkerstedt 외(2014, J Sleep Res 23:240) —
 *     KSS 7 이상(>6)은 운전 수행 저하·뇌파상 수면 침투와 특히 관련된다. 객관 지표만큼 민감하고 타당한 지표로 평가된다.
 *  [대화로 재는 신호]
 *  6. 응답 지연: 그날 출발 직후 측정한 운전자 본인의 기준값 대비 몇 배 느려졌는지.
 *  7. 정답률: 정답이 분명한 말놀이(계산·구구단·숫자 거꾸로 등)에서 최근에 얼마나 틀렸는지.
 *
 * 수면·KSS 구간은 위 연구를 따랐지만, 점수로 바꾸는 가중치는 기획 단계의 초기값이다.
 * 이 앱은 의료기기가 아니며 졸음을 진단하지 않는다. 운전자가 쉬도록 돕는 보조 수단이다.
 */
object RiskEngine {

    data class Input(
        val continuousDrivingMinutes: Int,
        val hourOfDay: Int,
        /** 최근 응답 지연 / 기준값. 측정 전이면 null. */
        val latencyRatio: Double?,
        /** 최근 질문에 무응답이 있었는지. */
        val recentNoResponse: Boolean = false,
        /** 지난 24시간 동안 잔 시간. 모르면 null. */
        val sleepHours: Double? = null,
        /** 일어난 뒤 지금까지 깨어 있은 시간. 모르면 null. */
        val hoursAwake: Double? = null,
        /** 최근(유효 시간 안의) KSS 자가평가 1~9. 없으면 null. */
        val kss: Int? = null,
        /** 최근 채점된 말놀이 대답 수와 그중 틀린 수. */
        val recentGraded: Int = 0,
        val recentWrong: Int = 0,
        /**
         * 같이하기(팀 게임) 중인지. 마이크 하나로는 누가 말했는지 확실히 가를 수 없어서,
         * 이때는 대화로 재는 신호(대답 속도·정답률)를 절반만 반영하고
         * 운행 상황·수면 신호(연속 주행·시간대·수면·깨어 있은 시간)를 1.2배로 본다. 가중치는 기획 초기값.
         */
        val teamMode: Boolean = false,
        /** 무응답이 몇 번 연달아 나왔는지. 한 번은 못 들었을 수도 있어 약하게, 두 번 연속부터 강하게 본다. */
        val noResponseStreak: Int = if (recentNoResponse) 1 else 0,
    )

    data class Result(val score: Int, val level: RiskLevel, val reasons: List<String>)

    fun evaluate(input: Input): Result {
        val reasons = mutableListOf<String>()
        var score = 0
        // 같이하기 중에는 대화 신호 비중을 낮추고 운행·수면 신호 비중을 높인다(Input.teamMode 설명 참고).
        fun context(points: Int): Int = if (input.teamMode) (points * 1.2).roundToInt() else points
        fun conversation(points: Int): Int = if (input.teamMode) (points * 0.5).roundToInt() else points

        val drive = input.continuousDrivingMinutes
        val driveScore = when {
            drive >= 120 -> 50
            drive >= 90 -> 35
            drive >= 60 -> 20
            drive >= 40 -> 10
            else -> 0
        }
        if (driveScore > 0) reasons += "연속 주행 ${drive}분"
        score += context(driveScore)

        val hour = ((input.hourOfDay % 24) + 24) % 24
        // 졸음 관련 사고는 새벽 2~6시에 가장 많고 오후 2~4시에 한 번 더 늘어난다(Horne & Reyner 1995, BMJ 310:565).
        // 그래서 2~5시를 가장 높게, 그 앞뒤(0~1시·6시)와 오후 2~3시·늦은 밤을 그다음으로 본다.
        val timeScore = when (hour) {
            in 2..5 -> 20
            0, 1, 6 -> 15
            14, 15 -> 10
            in 22..23 -> 10
            13, 16 -> 5
            else -> 0
        }
        if (timeScore > 0) reasons += "졸음이 잦은 시간대(${hour}시)"
        score += context(timeScore)

        val streak = maxOf(input.noResponseStreak, if (input.recentNoResponse) 1 else 0)
        val latencyScore = when {
            // 한 번 대답이 없는 것은 못 들었거나 다른 데 집중했을 수 있다. 두 번 연속이면 강한 신호로 본다.
            streak >= 2 -> 35
            streak == 1 -> 15
            input.latencyRatio == null -> 0
            input.latencyRatio >= 2.0 -> 30
            input.latencyRatio >= 1.5 -> 15
            else -> 0
        }
        if (streak >= 2) {
            reasons += "질문에 ${streak}번 연속 응답 없음"
        } else if (streak == 1) {
            reasons += "질문에 응답 없음"
        } else if (latencyScore > 0) {
            reasons += "응답이 평소보다 %.1f배 느림".format(input.latencyRatio)
        }
        score += conversation(latencyScore)

        // ── 수면의학 근거 신호 ──
        var floor = RiskLevel.NORMAL
        input.sleepHours?.let { h ->
            val sleepScore = when {
                h <= 2.0 -> 45
                h < 4.0 -> 40
                h < 5.0 -> 25
                h < 6.0 -> 12
                h < 7.0 -> 5
                else -> 0
            }
            if (sleepScore > 0) reasons += "지난 24시간 수면 ${formatHours(h)}"
            score += context(sleepScore)
            // 2시간 이하는 운전 부적합(전문가 합의), 4시간 미만은 사고율 11.5배라 처음부터 높은 단계로 본다.
            if (h <= 2.0) floor = maxOf(floor, RiskLevel.REST_ADVISED)
            else if (h < 4.0) floor = maxOf(floor, RiskLevel.WARNING)
        }
        input.hoursAwake?.let { a ->
            val awakeScore = when {
                a >= 24.0 -> 30
                a >= 17.0 -> 15
                else -> 0
            }
            if (awakeScore > 0) reasons += "깨어 있은 지 ${a.toInt()}시간"
            score += context(awakeScore)
        }
        input.kss?.let { k ->
            val kssScore = when {
                k >= 9 -> 50
                k == 8 -> 35
                k == 7 -> 20
                else -> 0
            }
            if (kssScore > 0) reasons += "졸림 자가평가 KSS $k"
            score += kssScore
            // 본인이 졸리다고 한 것은 가장 직접적인 신호라서, 점수와 별개로 최소 단계를 정한다.
            floor = maxOf(
                floor,
                when {
                    k >= 9 -> RiskLevel.REST_ADVISED
                    k == 8 -> RiskLevel.WARNING
                    k == 7 -> RiskLevel.CAUTION
                    else -> RiskLevel.NORMAL
                },
            )
        }

        // ── 정답률 ──
        if (input.recentGraded >= 3) {
            val wrongRate = input.recentWrong.toDouble() / input.recentGraded
            val accuracyScore = when {
                wrongRate >= 0.6 -> 15
                wrongRate >= 0.4 -> 8
                else -> 0
            }
            if (accuracyScore > 0) reasons += "최근 ${input.recentGraded}문제 중 ${input.recentWrong}개 틀림"
            score += conversation(accuracyScore)
        }

        score = score.coerceIn(0, 100)
        val level = maxOf(levelOf(score), floor)
        // 최소 단계(floor)로 단계가 올라갔으면 점수도 그 단계의 하한까지 맞춰, 화면의 숫자와 단계가 어긋나지 않게 한다.
        return Result(maxOf(score, minScoreOf(level)), level, reasons)
    }

    private fun minScoreOf(level: RiskLevel): Int = when (level) {
        RiskLevel.REST_ADVISED -> 70
        RiskLevel.WARNING -> 50
        RiskLevel.CAUTION -> 30
        RiskLevel.NORMAL -> 0
    }

    private fun formatHours(h: Double): String =
        if (h % 1.0 == 0.0) "${h.toInt()}시간" else "%.1f시간".format(h)

    fun levelOf(score: Int): RiskLevel = when {
        score >= 70 -> RiskLevel.REST_ADVISED
        score >= 50 -> RiskLevel.WARNING
        score >= 30 -> RiskLevel.CAUTION
        else -> RiskLevel.NORMAL
    }
}

/**
 * 단계를 내릴 때만 머뭇거리는(히스테리시스) 추적기.
 * 올라갈 때는 바로 반영하고, 내려갈 때는 같은 낮은 단계가 [downHold]번 연속 나와야 내린다.
 * 응답 하나가 우연히 빨랐다고 경고가 풀리지 않게 하기 위함이다.
 */
class RiskTracker(private val downHold: Int = 3) {
    var level: RiskLevel = RiskLevel.NORMAL
        private set
    private var lowerStreak = 0

    fun update(raw: RiskLevel): RiskLevel {
        if (raw.ordinal >= level.ordinal) {
            level = raw
            lowerStreak = 0
        } else {
            lowerStreak += 1
            if (lowerStreak >= downHold) {
                level = raw
                lowerStreak = 0
            }
        }
        return level
    }

    /** 휴식을 마치면 위험 단계를 처음부터 다시 계산한다. */
    fun reset() {
        level = RiskLevel.NORMAL
        lowerStreak = 0
    }
}

/** 대답의 종류. 생각해야 하는 문제는 원래 대답이 느리므로 기준값을 따로 둔다. */
enum class AnswerKind {
    /** 바로 답하는 질문: 수면 점검, 졸림(KSS), O/X, 지역 O/X, 쉼터 제안(응/아니). */
    QUICK,
    /** 생각해야 답하는 문제: 계산, 구구단, 숫자 거꾸로, 끝말잇기, 초성, 단어 기억, 넌센스, 거리 맞히기. */
    THINK,
}

/**
 * 응답 지연 기준값. 대답 종류([AnswerKind])마다 처음 [baselineSamples]개 응답의 중앙값을 그날의 기준으로 삼고,
 * 이후에는 최근 [windowSize]개 응답의 ‘기준 대비 배수’ 중앙값을 본다.
 * 사람마다 말하는 속도가 다르므로 절대값이 아니라 ‘오늘의 나’와 비교하고, 문제 난이도 차이가 졸음처럼 보이지 않게
 * 같은 종류끼리만 비교한다. 어떤 종류의 기준을 잡는 동안(처음 [baselineSamples]개)의 대답은 비교에 넣지 않는다.
 * (다른 종류의 기준과 비교하면 첫 계산 문제 두 개가 “2.5배 느림”으로 잡혀 거짓 졸음 신호가 된다.)
 */
class LatencyBaseline(
    private val baselineSamples: Int = 2,
    private val windowSize: Int = 3,
    /** 이 값보다 짧은 기준값은 쓰지 않는다(측정 오류 방지). */
    private val minBaselineMs: Long = 600,
) {
    private val initial = mutableMapOf<AnswerKind, MutableList<Long>>()
    private val baselines = linkedMapOf<AnswerKind, Long>()
    private val recent = ArrayDeque<Double>()

    /** 화면에 보이는 기준값: 바로 답하는 질문의 기준(없으면 먼저 잡힌 기준). */
    val baselineMs: Long?
        get() = baselines[AnswerKind.QUICK] ?: baselines.values.firstOrNull()
    var lastNoResponse: Boolean = false
        private set
    /** 무응답이 연달아 나온 횟수. 대답이 한 번이라도 오면 0. */
    var noResponseStreak: Int = 0
        private set

    fun record(latencyMs: Long?, kind: AnswerKind = AnswerKind.QUICK) {
        if (latencyMs == null) {
            lastNoResponse = true
            noResponseStreak++
            return
        }
        lastNoResponse = false
        noResponseStreak = 0
        val own = baselines[kind]
        if (own != null) {
            push(latencyMs.toDouble() / own)
            return
        }
        // 이 종류의 기준을 잡는 중. 이 대답들은 비교에 넣지 않는다.
        val list = initial.getOrPut(kind) { mutableListOf() }
        list += latencyMs
        if (list.size >= baselineSamples) baselines[kind] = median(list).coerceAtLeast(minBaselineMs)
    }

    private fun push(ratio: Double) {
        recent.addLast(ratio)
        while (recent.size > windowSize) recent.removeFirst()
    }

    /** 최근 응답의 기준 대비 배수(중앙값). 기준이 없거나 최근 응답이 적으면 null. */
    fun ratio(): Double? {
        if (baselines.isEmpty()) return null
        // 대답 하나가 우연히 느렸던 것만으로 판단하지 않도록, 기준 이후 대답이 두 개 이상 모였을 때만 비교한다.
        if (recent.size < 2) return null
        val sorted = recent.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** 새 운행: 기준값까지 모두 지운다. */
    fun reset() {
        initial.clear()
        baselines.clear()
        clearRecent()
    }

    /** 휴식 후에는 최근 기록만 비우고 기준값은 유지한다. */
    fun clearRecent() {
        recent.clear()
        lastNoResponse = false
        noResponseStreak = 0
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
