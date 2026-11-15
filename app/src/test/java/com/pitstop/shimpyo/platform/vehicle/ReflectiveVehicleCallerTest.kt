package com.pitstop.shimpyo.platform.vehicle

import com.pitstop.shimpyo.platform.vehicle.fake.constant.control.MoveControl
import com.pitstop.shimpyo.platform.vehicle.fake.model.area.SeatArea
import com.pitstop.shimpyo.platform.vehicle.fake.model.area.SeatValue
import com.pitstop.shimpyo.platform.vehicle.fake.model.area.ZoneArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pleos Vehicle SDK와 같은 모양(check/getCurrent/set + 콜백)의 가짜 도메인 */
class FakeHvac {
    enum class AccessType { NONE, READ, READ_WRITE }
    data class CapabilityConfig(val accessType: AccessType)
    data class AreaBounds(val minValue: Int, val maxValue: Int)

    var fan = 3
    var lastArea: SeatArea? = null

    fun checkHvacFanSpeedCapabilities(
        onComplete: (CapabilityConfig, Map<SeatArea, AreaBounds>) -> Unit,
        onFailed: (Exception) -> Unit,
    ) = onComplete(CapabilityConfig(AccessType.READ_WRITE), mapOf(SeatArea(SeatValue.ROW_1_LEFT) to AreaBounds(1, 8)))

    fun getCurrentHvacFanSpeed(area: SeatArea, onComplete: (Int?) -> Unit, onFailed: (Exception) -> Unit) = onComplete(fan)

    fun setHvacFanSpeed(area: SeatArea, value: Int, onComplete: (SeatArea, Int?) -> Unit, onFailed: (Exception) -> Unit) {
        lastArea = area
        if (value > 8) onFailed(IllegalArgumentException("out of range")) else {
            fan = value; onComplete(area, value)
        }
    }

    fun setHvacFanSpeedMove(area: SeatArea, value: Int, onComplete: (SeatArea, Int?) -> Unit, onFailed: (Exception) -> Unit) {
        error("should not be picked")
    }

    fun checkHvacRecirculationCapabilities(onComplete: (CapabilityConfig, List<SeatArea>) -> Unit, onFailed: (Exception) -> Unit) =
        onComplete(CapabilityConfig(AccessType.READ), emptyList())
}

/** 실제 SDK처럼 가변 인자 영역·MoveControl을 쓰는 가짜 시트·공조. */
class FakeZoned {
    data class AreaBounds(val minValue: Int, val maxValue: Int)
    val frontRow = ZoneArea(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT)
    var lastSetArea: ZoneArea? = null
    var lastMove: MoveControl? = null
    var lastReadArea: ZoneArea? = null

    fun checkHvacTemperatureCapabilities(onComplete: (Map<ZoneArea, AreaBounds>) -> Unit, onFailed: (Exception) -> Unit) =
        onComplete(mapOf(frontRow to AreaBounds(16, 30)))

    fun setHvacTemperature(area: ZoneArea, value: Int, onComplete: (ZoneArea, Int?) -> Unit, onFailed: (Exception) -> Unit) {
        lastSetArea = area
        // 실제 차량처럼 지원하지 않는 영역이면 거절한다.
        if (area != frontRow) onFailed(IllegalArgumentException("areaId is Invalid")) else onComplete(area, value)
    }

    fun getCurrentSeatBackrestAnglePosition(area: ZoneArea, onComplete: (Int?) -> Unit, onFailed: (Exception) -> Unit) {
        lastReadArea = area
        if (area.values.isEmpty()) onFailed(IllegalArgumentException("empty area")) else onComplete(5)
    }

    fun moveSeatBackrestAngle(area: ZoneArea, control: MoveControl, onComplete: (ZoneArea, MoveControl) -> Unit, onFailed: (Exception) -> Unit) {
        lastMove = control
        onComplete(area, control)
    }
}

class ReflectiveVehicleCallerTest {
    private val zoned = FakeZoned()

    @Test
    fun varargAreaIsBuiltWithDriverSeatNotEmpty() {
        var value: Any? = null
        caller.invoke(zoned, caller.findExact(zoned, "getCurrentSeatBackrestAnglePosition")!!, null, { value = it.first() }, { throw it })
        assertEquals(5, value)
        assertEquals(listOf(SeatValue.ROW_1_LEFT), zoned.lastReadArea!!.values.toList())
    }

    @Test
    fun moveZeroIsStopNotTheFirstConstant() {
        val move = caller.findExact(zoned, "moveSeatBackrestAngle")!!
        caller.invoke(zoned, move, 0, { }, { throw it })
        assertEquals(MoveControl.STOP, zoned.lastMove)
        caller.invoke(zoned, move, -2, { }, { throw it })
        assertEquals(MoveControl.BACKWARD, zoned.lastMove)
        caller.invoke(zoned, move, 1, { }, { throw it })
        assertEquals(MoveControl.FORWARD, zoned.lastMove)
    }

    @Test
    fun capabilityAreaIsUsedForZonedProperties() {
        var cap: ReflectiveVehicleCaller.Capability? = null
        caller.invoke(zoned, caller.findExact(zoned, "checkHvacTemperatureCapabilities")!!, null, { cap = caller.parseCapability(it) }, { throw it })
        assertEquals(zoned.frontRow, cap!!.area)
        val set = caller.findExact(zoned, "setHvacTemperature")!!
        // 운전석 하나로 만든 영역은 거절되고, capability 영역은 받아들여진다.
        var failed: Throwable? = null
        caller.invoke(zoned, set, 21, { }, { failed = it })
        assertNotNull(failed)
        var ok = false
        caller.invoke(zoned, set, 21, { ok = true }, { throw it }, area = cap!!.area)
        assertTrue(ok)
        assertEquals(zoned.frontRow, zoned.lastSetArea)
    }

    private val caller = ReflectiveVehicleCaller()
    private val hvac = FakeHvac()

    @Test
    fun findsShortestMatchingSetterAndSkipsExcluded() {
        assertEquals("setHvacFanSpeed", caller.find(hvac, "set", listOf("FanSpeed"))?.name)
        assertEquals("setHvacFanSpeed", caller.find(hvac, "set", listOf("FanSpeed"), exclude = listOf("Move"))?.name)
        assertNull(caller.find(hvac, "set", listOf("Backrest")))
    }

    @Test
    fun parsesCapabilityBoundsForDriverArea() {
        var cap: ReflectiveVehicleCaller.Capability? = null
        caller.invoke(hvac, caller.findExact(hvac, "checkHvacFanSpeedCapabilities")!!, null, { cap = caller.parseCapability(it) }, { throw it })
        assertNotNull(cap)
        assertTrue(cap!!.writable)
        assertEquals(1.0, cap!!.bounds!!.min, 0.0)
        assertEquals(8.0, cap!!.bounds!!.max, 0.0)
    }

    @Test
    fun readOnlyCapabilityIsNotWritable() {
        var cap: ReflectiveVehicleCaller.Capability? = null
        caller.invoke(hvac, caller.findExact(hvac, "checkHvacRecirculationCapabilities")!!, null, { cap = caller.parseCapability(it) }, { throw it })
        assertFalse(cap!!.writable)
    }

    @Test
    fun getAndSetBuildDriverAreaAndRouteCallbacks() {
        var current: Any? = null
        caller.invoke(hvac, caller.findExact(hvac, "getCurrentHvacFanSpeed")!!, null, { current = it.first() }, { throw it })
        assertEquals(3, current)

        var ok = false
        caller.invoke(hvac, caller.find(hvac, "set", listOf("FanSpeed"))!!, 6.4, { ok = true }, { throw it })
        assertTrue(ok)
        assertEquals(6, hvac.fan)
        assertEquals(SeatValue.ROW_1_LEFT, hvac.lastArea?.seatValue)

        var failed: Throwable? = null
        caller.invoke(hvac, caller.find(hvac, "set", listOf("FanSpeed"))!!, 99, { }, { failed = it })
        assertNotNull(failed)
    }
}
