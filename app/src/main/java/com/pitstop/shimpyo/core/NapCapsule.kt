package com.pitstop.shimpyo.core

/**
 * 낮잠 캡슐: 쉼터에서 쉬는 15분을 ‘커피 낮잠 + 빛으로 깨우기’로 만든다.
 *
 * - 커피 낮잠: 카페인을 마시고 바로 15분 안쪽으로 자면, 깰 즈음 카페인이 돌기 시작해 둘 중 하나만 할 때보다
 *   졸음이 더 줄어든다(Reyner & Horne 1997, Psychophysiology 34:721 — 운전 시뮬레이터, 카페인 150mg + 15분 이하 낮잠).
 *   15분을 넘기면 깊은 잠에 들어 깬 직후 더 멍해질 수 있어(수면 관성) 휴식 시간을 늘리지 않는다.
 * - 빛으로 깨우기: 소리로 갑자기 깨우지 않고, 끝나기 몇 분 전부터 무드등을 단계적으로 밝히고 실내를 조금 시원하게 한다.
 *   마지막에 목소리로 알린 뒤 등받이를 세우고 창문을 연다.
 *
 * 단계 값(밝기·온도·몇 분 전)은 기획 초기값이다.
 */
object NapCapsule {
    /** 끝나기 몇 분 전부터 깨우기 시작하는지. */
    const val WAKE_LEAD_MINUTES = 3

    /** 깨우는 동안의 실내 온도(휴식 22도보다 조금 시원하게). */
    const val WAKE_CELSIUS = 20f

    /** 깨우기 단계. [light]는 무드등 밝기(최댓값 대비 비율). */
    enum class WakeStep(val light: Float, val cool: Boolean, val label: String) {
        DAWN(0.4f, cool = false, label = "새벽빛"),
        MORNING(0.7f, cool = true, label = "아침빛"),
        DAY(1.0f, cool = false, label = "한낮빛"),
    }

    /**
     * 남은 시간으로 지금 깨우기 단계를 고른다. 아직 깨울 때가 아니면 null.
     * 휴식이 짧으면(시연용 등) 앞쪽 단계를 건너뛰고 남은 분 수만큼만 쓴다.
     */
    fun stepAt(remainingSec: Int, restMinutes: Int): WakeStep? {
        val lead = minOf(WAKE_LEAD_MINUTES, (restMinutes - 1).coerceAtLeast(0))
        if (lead <= 0 || remainingSec <= 0 || remainingSec > lead * 60) return null
        // 남은 시간이 lead분 → 1분 사이일 때 0, 1, 2…(마지막 1분이 가장 밝다)
        val index = (lead - 1 - (remainingSec - 1) / 60) + (WakeStep.entries.size - lead)
        return WakeStep.entries[index.coerceIn(0, WakeStep.entries.size - 1)]
    }

    /** 휴식을 시작할 때 하는 말. */
    fun restStartLine(restMinutes: Int): String =
        "${restMinutes}분 낮잠 캡슐을 시작할게요. 원하시면 커피를 한 잔 드시고 바로 눈을 붙이세요. " +
            "깰 즈음 카페인이 돌아서 더 개운해요. 시트를 눕히고 실내를 어둡게 할게요."

    /** 휴식이 끝날 때(빛으로 깨운 뒤) 하는 말. */
    fun wakeLine(restMinutes: Int, seatWillRise: Boolean, windowWillOpen: Boolean = true): String {
        // 실제로 할 일만 말한다(창문은 주차 중에만 연다).
        val actions = listOfNotNull(
            if (seatWillRise) "등받이를 천천히 세울게요" else null,
            if (windowWillOpen) "창문을 잠깐 열어 바깥 공기를 넣을게요" else null,
        )
        val tail = when (actions.size) {
            2 -> "등받이를 천천히 세우고 창문을 잠깐 열어 바깥 공기를 넣을게요."
            1 -> actions.first() + "."
            else -> "천천히 다시 출발해요."
        }
        return "잘 쉬셨어요? ${restMinutes}분 지났어요. $tail"
    }

    /** 다시 출발할 때 하는 말. 깬 직후 멍한 느낌(수면 관성)은 몇 분 가므로 짧게 덧붙인다. */
    // 목적지 안내는 순정 내비의 몫이라 “목적지로 안내할게요”라고 약속하지 않는다.
    // 경유지를 지웠다고도 말하지 않는다(도착한 경유지는 지우지 않고, 경유지 없이 쉰 경우도 있다).
    const val DEPART_LINE = "개운하게 다시 출발해요. 아직 멍하면 출발 전에 차 밖에서 1분만 걸어 보세요. 목적지가 있으면 내비가 이어서 안내해요."
}
