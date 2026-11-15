package com.pitstop.shimpyo.platform.vehicle

import com.pitstop.shimpyo.core.ControlResult
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 위치를 읽으며 움직이는 등받이 제어. 시계·차량 응답을 바꿔 시간 초과와 늦은 응답도 시험한다.
 * 실패하거나 목표에 못 닿으면 원래 위치를 보존한다. 다음 복귀 시도에서 다시 쓸 수 있어야 한다.
 */
internal class BackrestMotion(
    private val caller: ReflectiveVehicleCaller,
    private val parked: () -> Boolean,
    private val post: (() -> Unit) -> Unit,
    private val later: (Long, () -> Unit) -> (() -> Unit),
    private val log: (String) -> Unit,
) {
    private var generation = 0
    private var original: Double? = null
    private var cancelActive: (() -> Unit)? = null

    fun cancel() {
        generation++
        val cancel = cancelActive
        cancelActive = null
        cancel?.invoke()
    }

    fun move(seat: Any, recline: Boolean, done: (ControlResult) -> Unit) {
        cancel()
        val gen = generation
        val move = caller.findExact(seat, "moveSeatBackrestAngle") ?: return done(ControlResult.UNSUPPORTED)
        val get = caller.findExact(seat, "getCurrentSeatBackrestAnglePosition") ?: return done(ControlResult.UNSUPPORTED)
        var finished = false
        var started = false
        var moveArea: Any? = null
        var positionArea: Any? = null
        var cancelTimer: (() -> Unit)? = null
        fun stop(after: (Boolean) -> Unit = {}) {
            if (!started) { after(true); return }
            caller.invoke(seat, move, 0, area = moveArea,
                onSuccess = { post { after(true) } },
                onFailure = { e -> post { log("등받이 멈춤 실패: ${e.message}"); after(false) } })
        }
        fun finish(result: ControlResult) {
            if (finished) return
            finished = true
            cancelTimer?.invoke()
            if (gen == generation) cancelActive = null
            done(result)
        }
        fun active(): Boolean = !finished && gen == generation
        fun allowed(): Boolean {
            if (!active()) return false
            if (recline && !parked()) {
                stop()
                finish(ControlResult.FAILED)
                return false
            }
            return true
        }
        // 이전 요청의 완료 통지는 다음 큐로 보낸다. 새 요청을 설정하는 중 복귀 요청이 재진입하지 않게 한다.
        cancelActive = { stop(); later(0) { finish(ControlResult.FAILED) }; Unit }
        cancelTimer = later(20_000) {
            if (active()) {
                stop()
                log("등받이 응답 시간 초과 · 멈춤 요청")
                finish(ControlResult.FAILED)
            }
        }
        if (!allowed()) return
        fun read(next: (Double?) -> Unit) {
            caller.invoke(seat, get, null, area = positionArea,
                onSuccess = { args -> post { if (allowed()) next(args.firstNotNullOfOrNull { it as? Number }?.toDouble()?.takeIf { it.isFinite() }) } },
                onFailure = { post { if (allowed()) next(null) } })
        }
        fun capability(name: String, next: (ReflectiveVehicleCaller.Capability?) -> Unit) {
            val check = caller.findExact(seat, name) ?: return next(null)
            caller.invoke(seat, check, null,
                onSuccess = { args -> post { if (allowed()) next(caller.parseCapability(args)) } },
                onFailure = { post { if (allowed()) next(null) } })
        }
        capability("checkMoveSeatBackrestAngleCapabilities") moveCap@{ moveCap ->
            if (moveCap != null && !moveCap.writable) { finish(ControlResult.UNSUPPORTED); return@moveCap }
            moveArea = moveCap?.area
            capability("checkSeatBackrestAnglePositionCapabilities") positionCap@{ positionCap ->
                positionArea = positionCap?.area
                read start@{ start ->
                    if (start == null) { finish(ControlResult.UNSUPPORTED); return@start }
                    val target = if (recline) {
                        val bounds = positionCap?.bounds ?: run { finish(ControlResult.UNSUPPORTED); return@start }
                        if (original == null) original = start
                        minOf(start, bounds.min + (bounds.max - bounds.min) * 0.25)
                    } else original ?: run { finish(ControlResult.DONE); return@start }
                    // 운전자가 이미 더 세웠다면 복귀라는 이름으로 다시 눕히지 않는다.
                    if (abs(target - start) < 0.5 || (!recline && start >= target)) {
                        if (!recline) original = null
                        finish(ControlResult.DONE); return@start
                    }
                    val down = target < start
                    // 원위치가 현재보다 더 누운 위치라면 주차 확인 없이는 움직이지 않는다.
                    if (down && !parked()) { finish(ControlResult.FAILED); return@start }
                    val speed = moveCap?.bounds?.let { max(abs(it.min), abs(it.max)) }?.coerceAtLeast(1.0) ?: 1.0
                    val signed = (if (down) -speed else speed).roundToInt()
                    var polls = 0
                    fun poll() {
                        later(300) {
                            if (allowed()) read { now ->
                                polls++
                                val reached = now != null && (if (down) now <= target else now >= target)
                                if (reached || now == null || polls >= 40) {
                                    stop { stopped ->
                                        if (active()) {
                                            val ok = reached && stopped
                                            if (ok && !recline) original = null
                                            log("등받이: $start → ${now ?: "?"} (목표 $target)")
                                            finish(if (ok) ControlResult.DONE else ControlResult.FAILED)
                                        }
                                    }
                                } else poll()
                            }
                        }
                    }
                    if (!allowed()) return@start
                    started = true
                    caller.invoke(seat, move, signed, area = moveArea,
                        onSuccess = { post { if (allowed()) poll() } },
                        onFailure = { post { if (active()) { stop(); finish(ControlResult.FAILED) } } })
                }
            }
        }
    }
}
