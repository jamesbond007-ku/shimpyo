package com.pitstop.shimpyo.core

/**
 * 웨어러블 수면 기록으로 출발할 때 실내를 미리 맞춰 두는 정도.
 *
 * 운전자가 말하지 않아도 워치 기록만으로 그날 졸음이 오기 쉬운지 가늠해서,
 * 시동을 걸자마자(졸음이 오기 전에) 실내를 각성에 유리하게 바꿔 둔다.
 * 구간은 [RiskEngine]과 같은 근거를 쓴다.
 *  - 수면 5시간 미만: Tefft(2016) 기준 사고율 4배 이상 구간 → 강하게
 *  - 깨어 있은 지 17시간 이상: Williamson & Feyer(2000) 혈중알코올 0.05% 상당 → 강하게
 *  - 수면 5~7시간: 사고율 1.3~1.9배 구간 → 가볍게
 *  - 7시간 이상이고 오래 깨어 있지 않으면 평소대로
 * 온도 값은 기획 초기값이다(시원한 실내가 각성에 도움이 된다는 일반 권고). 실차에서 조정이 필요하다.
 */
enum class CabinPrep(val label: String) {
    /** 평소대로. 아무것도 바꾸지 않는다. */
    NONE("평소"),
    /** 실내 온도만 살짝 낮춘다. */
    REFRESH("상쾌하게"),
    /** 외기를 넣고 송풍을 올리고 실내를 시원하게. 첫 휴식도 앞당긴다. */
    ALERT("각성"),
}

object SleepPrep {
    const val REFRESH_CELSIUS = 22f
    const val ALERT_CELSIUS = 21f
    /** 잠이 많이 부족한 날 첫 휴식 제안 시점(연속 주행 분). 도로공사 ‘2시간마다 휴식’보다 앞당긴다. */
    const val ALERT_FIRST_REST_MINUTE = 60

    data class Plan(
        val prep: CabinPrep,
        /** 맞출 실내 온도. NONE이면 null. */
        val celsius: Float?,
        /** 외기 유입·송풍 강화를 켤지. */
        val freshAir: Boolean,
        /** 첫 휴식 제안 시점. 앞당길 필요가 없으면 null. */
        val firstRestMinute: Int?,
        /** ALERT가 수면 부족 때문인지(false면 오래 깨어 있어서). 알리는 말에 쓴다. */
        val shortSleep: Boolean = true,
    )

    fun plan(sleepHours: Double, hoursAwake: Double?): Plan {
        val awake = hoursAwake ?: 0.0
        return when {
            sleepHours < 5.0 || awake >= 17.0 -> Plan(
                CabinPrep.ALERT, ALERT_CELSIUS, freshAir = true, firstRestMinute = ALERT_FIRST_REST_MINUTE,
                shortSleep = sleepHours < 5.0,
            )
            sleepHours < 7.0 -> Plan(CabinPrep.REFRESH, REFRESH_CELSIUS, freshAir = false, firstRestMinute = null)
            else -> Plan(CabinPrep.NONE, null, freshAir = false, firstRestMinute = null)
        }
    }

    /** 실내를 바꾸기 전에 먼저 알리는 한 문장(안전 원칙: 차량을 움직이기 전에 음성으로 알린다). NONE이면 null. */
    fun announcement(plan: Plan): String? = when (plan.prep) {
        CabinPrep.NONE -> null
        CabinPrep.REFRESH ->
            "잠이 조금 부족한 날이라 실내 온도를 ${plan.celsius!!.toInt()}도로 살짝 낮춰 둘게요."
        CabinPrep.ALERT ->
            (if (plan.shortSleep) "잠이 많이 부족한 날이라" else "일어나신 지 오래돼서") + " 바깥 공기를 넣고 실내를 ${plan.celsius!!.toInt()}도로 시원하게 해 둘게요." +
                (plan.firstRestMinute?.let { " ${if (it % 60 == 0) "${it / 60}시간" else "${it}분"}쯤 지나면 한 번 쉬어 가요." } ?: "")
    }
}
