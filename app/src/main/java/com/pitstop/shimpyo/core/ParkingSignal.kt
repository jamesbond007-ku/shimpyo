package com.pitstop.shimpyo.core

/**
 * 기어와 주차 브레이크 신호로 ‘주차’를 판단한다. 시트를 눕히고 창문을 여는 근거라서 엄격하게 본다.
 *  - 기어를 알면 기어만 본다: P단이면 주차, 아니면(D·N·R) 주차 브레이크가 걸려 있어도 주차가 아니다.
 *    (신호 대기·정체 중에 주차 브레이크만 건 상태를 주차로 보면 도로 위에서 휴식이 시작된다.)
 *  - 기어 신호가 없는 차에서만 주차 브레이크로 대신한다.
 *  - 둘 다 모르면 null(주차로 보지 않음).
 */
object ParkingSignal {
    fun combine(
        gearPark: Boolean?,
        parkingBrakeOn: Boolean?,
        // 기어를 제공하는 차에서 일시적으로 값을 못 받는 것은 ‘기어 없는 차’와 다르다.
        gearSupported: Boolean = gearPark != null,
    ): Boolean? = when {
        gearSupported -> gearPark
        parkingBrakeOn != null -> parkingBrakeOn
        else -> null
    }
}
