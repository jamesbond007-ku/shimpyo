package com.pitstop.shimpyo.platform.vehicle.fake.model.area

/** Pleos의 ai.pleos.playground.vehicle.api.model.area.SeatArea와 같은 모양의 테스트용 클래스 */
enum class SeatValue { ROW_1_RIGHT, ROW_1_LEFT, ROW_2_LEFT }

data class SeatArea(val seatValue: SeatValue)

/**
 * 실제 Pleos SeatArea와 같은 모양: 생성자가 가변 인자(SeatValue...)이고 equals는 값 배열로 비교한다.
 * 공조처럼 ‘1열 전체’로 묶인 영역은 ZoneArea(ROW_1_LEFT, ROW_1_RIGHT)가 된다.
 */
class ZoneArea(vararg val values: SeatValue) {
    override fun equals(other: Any?) = other is ZoneArea && values.contentEquals(other.values)
    override fun hashCode() = values.contentHashCode()
}