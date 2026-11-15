package com.pitstop.shimpyo.core

/**
 * 차량이 알려 주는 ‘주행 시간’ 값을 연속 주행 분으로 바꾼다.
 * Pleos SDK 문서만으로는 단위(분·초)와 시동을 껐다 켤 때의 동작을 확정할 수 없어서(실차 미검증), 값을 그대로 믿지 않는다.
 *  - 단위: 처음 [checkAfterMs] 동안 값이 실제 시간보다 훨씬 빨리 늘면 초 단위로 보고 60으로 나눈다.
 *    확인이 끝나기 전에는 값을 쓰지 않는다(null → 앱 시계로 계산). 초 단위 값을 분으로 읽으면 출발 3분 만에
 *    “연속 주행 180분”이 되어 바로 쉼터를 권하게 된다.
 *  - 되감김: 값이 줄면(시동을 껐다 켜서 0부터 다시 셈) 그 전까지의 값을 이어 붙인다.
 *    잠깐 시동을 끈 것만으로 ‘쉰 것’이 되지 않게 하기 위해서다(쉬었는지는 휴식 단계가 판단한다).
 */
class VehicleTripTime(private val checkAfterMs: Long = 3 * 60_000L) {
    private var carryRaw = 0L
    private var lastRaw: Int? = null
    private var probeRaw: Long? = null
    private var probeAtMs = 0L
    private var unitChecked = false

    /** 초 단위로 판정됐는지(진단 화면용). */
    var unitSeconds = false
        private set

    fun record(raw: Int, nowMs: Long) {
        if (raw < 0) return
        val prev = lastRaw
        if (prev != null && raw < prev) carryRaw += prev
        lastRaw = raw
        val total = carryRaw + raw
        if (!unitChecked) {
            val p = probeRaw
            if (p == null) {
                probeRaw = total
                probeAtMs = nowMs
            } else if (nowMs - probeAtMs >= checkAfterMs) {
                val realMinutes = (nowMs - probeAtMs) / 60_000.0
                val grew = total - p
                if (grew <= 0) {
                    // 그동안 차가 서 있어서 값이 늘지 않았다. 단위를 정할 수 없으니 지금부터 다시 잰다.
                    probeRaw = total
                    probeAtMs = nowMs
                } else {
                    // 분 단위라면 실제로 흐른 분보다 많이 늘 수 없다. 열 배 넘게 늘면 초 단위다.
                    unitSeconds = grew > realMinutes * 10
                    unitChecked = true
                }
            }
        }
    }

    /** 지금까지의 연속 주행 시간(분). 아직 모르거나 믿을 수 없으면 null. */
    fun minutes(): Int? {
        val r = lastRaw ?: return null
        val total = carryRaw + r
        if (!unitChecked) return null
        return (if (unitSeconds) total / 60 else total).toInt()
    }
}
