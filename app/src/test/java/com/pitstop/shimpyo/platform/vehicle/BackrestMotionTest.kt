package com.pitstop.shimpyo.platform.vehicle

import com.pitstop.shimpyo.core.ControlResult
import com.pitstop.shimpyo.platform.vehicle.fake.model.area.SeatArea
import com.pitstop.shimpyo.platform.vehicle.fake.model.area.SeatValue
import org.junit.Assert.*
import org.junit.Test

/** 실제 SDK와 같은 비동기 메서드 모양으로 응답 누락·실패·역순 도착을 만든다. */
class MotionSeat {
    data class Bounds(val minValue: Int, val maxValue: Int)
    var position = 80
    var holdRead = false
    var pendingRead: ((Int?) -> Unit)? = null
    var failStop = false
    var jumpToTarget = true
    var dropMoveReply = false
    val commands = mutableListOf<Int>()
    fun checkSeatBackrestAnglePositionCapabilities(onComplete: (Map<SeatArea, Bounds>) -> Unit, onFailed: (Exception) -> Unit) =
        onComplete(mapOf(SeatArea(SeatValue.ROW_1_LEFT) to Bounds(0, 100)))
    fun getCurrentSeatBackrestAnglePosition(onComplete: (Int?) -> Unit, onFailed: (Exception) -> Unit) {
        if (holdRead) pendingRead = onComplete else onComplete(position)
    }
    fun moveSeatBackrestAngle(value: Int, onComplete: () -> Unit, onFailed: (Exception) -> Unit) {
        commands += value
        if (value == 0 && failStop) { onFailed(IllegalStateException("정지 응답 실패")); return }
        if (value != 0 && jumpToTarget) position = if (value < 0) 25 else 80
        if (!dropMoveReply || value == 0) onComplete()
    }
}

class BackrestMotionTest {
    private data class Task(val at: Long, val run: () -> Unit, var cancelled: Boolean = false)
    private var time = 0L
    private val tasks = mutableListOf<Task>()
    private var parked = true
    private val seat = MotionSeat()
    private val motion = BackrestMotion(ReflectiveVehicleCaller(), { parked }, { it() }, { ms, fn ->
        val task = Task(time + ms, fn); tasks += task
        val cancel: () -> Unit = { task.cancelled = true }; cancel
    }, {})
    private fun advance(ms: Long) {
        val end = time + ms
        while (true) {
            val task = tasks.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
            tasks.remove(task); time = task.at; task.run()
        }
        time = end
    }
    private fun recline() {
        var result: ControlResult? = null
        motion.move(seat, true) { result = it }; advance(300)
        assertEquals(ControlResult.DONE, result)
    }

    @Test fun missingMoveReplyStillSendsStopAtDeadline() {
        seat.dropMoveReply = true
        var result: ControlResult? = null
        motion.move(seat, true) { result = it }; advance(20_000)
        assertEquals(listOf(-1, 0), seat.commands)
        assertEquals(ControlResult.FAILED, result)
    }

    @Test fun latePositionReplyAfterTimeoutCannotStartMotor() {
        seat.holdRead = true
        motion.move(seat, true) {}; advance(20_000)
        seat.pendingRead!!(80)
        assertTrue(seat.commands.isEmpty())
    }

    @Test fun parkingLossBeforePositionReplyCannotRecline() {
        seat.holdRead = true
        motion.move(seat, true) {}
        parked = false; seat.pendingRead!!(80)
        assertTrue(seat.commands.isEmpty())
    }

    @Test fun cancelledReclineReplyCannotRestartMotor() {
        seat.holdRead = true
        motion.move(seat, true) {}
        val late = seat.pendingRead!!
        seat.holdRead = false
        motion.move(seat, false) {}; advance(0)
        late(80)
        assertTrue(seat.commands.isEmpty())
    }

    @Test fun missingPositionReplyDuringMotionSendsStop() {
        motion.move(seat, true) {}
        seat.holdRead = true
        advance(20_000)
        assertEquals(listOf(-1, 0), seat.commands)
        seat.pendingRead!!(25)
        assertEquals(listOf(-1, 0), seat.commands)
    }

    @Test fun failedRestoreKeepsOriginalForRetry() {
        recline()
        seat.jumpToTarget = false
        var result: ControlResult? = null
        motion.move(seat, false) { result = it }; advance(12_000)
        assertEquals(ControlResult.FAILED, result)
        seat.jumpToTarget = true
        motion.move(seat, false) { result = it }; advance(300)
        assertEquals(ControlResult.DONE, result)
        assertEquals(80, seat.position)
        assertEquals(2, seat.commands.count { it > 0 })
    }

    @Test fun failedStopIsNotReportedAsDone() {
        seat.failStop = true
        var result: ControlResult? = null
        motion.move(seat, true) { result = it }; advance(300)
        assertEquals(ControlResult.FAILED, result)
    }

    @Test fun cancellationStopsMotorBeforeNewRequest() {
        motion.move(seat, true) {}
        motion.move(seat, false) {}; advance(300)
        assertEquals(listOf(-1, 0, 1, 0), seat.commands)
        assertEquals(80, seat.position)
    }
    @Test fun restoreNeverReclinesASeatTheDriverAlreadyRaised() {
        recline()
        seat.position = 90
        parked = false
        val before = seat.commands.size
        var result: ControlResult? = null
        motion.move(seat, false) { result = it }
        assertEquals(ControlResult.DONE, result)
        assertEquals(before, seat.commands.size)
        assertEquals(90, seat.position)
    }

}
