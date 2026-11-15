package com.pitstop.shimpyo.platform.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.listener.DrivingTimeListener
import ai.pleos.playground.vehicle.listener.SeatBeltNotBuckledWarningListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import android.content.Context
import android.util.Log
import com.pitstop.shimpyo.core.Backend
import com.pitstop.shimpyo.core.ControlResult
import com.pitstop.shimpyo.core.VehicleGateway
import com.pitstop.shimpyo.platform.MainThread
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pleos Vehicle SDK(ai.pleos.playground:Vehicle) 기반 차량 게이트웨이.
 *
 *  - 주차 여부: AAOS 표준 GEAR_SELECTION·PARKING_BRAKE_ON([AaosDrivingSignals]).
 *    Pleos Vehicle SDK 2.3.x는 자체 기어 API 대신 AAOS GEAR_SELECTION 사용을 권고한다.
 *  - 주행 시간: Odometer.registerTripCurrentDrivingTime(DrivingTimeListener)
 *  - 동승자: Seat.registerSeatOccupancy(SeatArea, SeatOccupancyListener) — 운전석(ROW_1_LEFT)을 뺀 모든 좌석.
 *    착좌 센서를 읽을 수 없는 차는 Safety.registerSeatBeltNotBuckledWarning을 보조로 쓴다.
 *  - 공조·시트·창문·실내등: [ReflectiveVehicleCaller]로 이름을 찾아 호출하고,
 *    capability로 지원 여부와 값 범위를 먼저 확인한다. 범위를 모르면 값을 추측하지 않고 UNSUPPORTED.
 *
 * 값의 방향은 AAOS VHAL 정의를 따른다(Pleos API 정책: AAOS와 같은 기능은 AAOS에 맞춘다).
 *  - SEAT_BACKREST_ANGLE_1_POS: 최솟값 = 완전히 젖힘, 최댓값 = 가장 세움
 *  - 창문 위치: 0 = 닫힘, 값이 클수록 열림 (공식 샘플 기준 에뮬레이터 운전석 범위 0..10)
 */
class PleosVehicleGateway(context: Context) : VehicleGateway {

    override val backend = Backend.PLEOS

    private val appContext = context.applicationContext
    private val caller = ReflectiveVehicleCaller()
    private val saved = SavedVehicleValues()

    private var vehicle: Vehicle? = null
    private var listener: VehicleGateway.Listener? = null
    private var signals: AaosDrivingSignals? = null
    private var aaosParked: Boolean? = null
    private var lastControlParked: Boolean? = null
    private var manualParked: Boolean? = null
    private var tripAttempts = 0

    // 동승자 신호
    private val occupancy = mutableMapOf<String, Boolean?>()
    private val passengerUndo = mutableListOf<() -> Unit>()
    private var occupancyReadable = false
    private var beltReadable = false
    /** 안전벨트 경고를 한 번이라도 봤는지. 주차하면 지운다(쉼터에서 내렸을 수 있으므로 말을 거는 쪽으로 돌아간다). */
    private var beltSeen = false
    private var manualPassenger: Boolean? = null
    private var lastPassenger: Boolean? = null
    private var passengerEmitted = false
    private var passengerAttempts = 0
    /** 진단 화면용: 동승자 신호를 어디서 받고 있는지. */
    private var passengerSource = "확인 중"

    private val tripListener = object : DrivingTimeListener {
        override fun onDrivingTimeUpdated(time: Int?) {
            // 단위는 분으로 본다(계기판 ‘주행 시간’과 같은 값). 실차 검증 때 다시 확인한다.
            if (time != null) MainThread.post { listener?.onTripDrivingMinutes(time.coerceAtLeast(0)) }
        }

        override fun onFailed(e: Exception) {
            MainThread.post { retryTripTime(e) }
        }
    }

    override fun start(listener: VehicleGateway.Listener) {
        aaosParked = null
        this.listener = listener
        vehicle = try {
            Vehicle(appContext).also { it.initialize() }
        } catch (t: Throwable) {
            log("Vehicle SDK 초기화 실패: ${t.message}")
            null
        }
        if (vehicle != null) log("Vehicle SDK 연결")
        registerTripTime()
        registerPassengerSignals()
        // Car.createCar()는 차량 서비스에 붙을 때까지 기다린다. 부팅 직후 서비스가 늦으면 앱 시작이 멈추므로(ANR)
        // 메인 스레드가 아닌 곳에서 연결하고, 결과만 메인 스레드로 넘긴다.
        val startedFor = listener
        kotlin.concurrent.thread(name = "aaos-connect") {
            val s = AaosDrivingSignals.connect(appContext) { parked ->
                if (this.listener === startedFor) {
                    aaosParked = parked
                    emitParked()
                }
            }
            MainThread.post {
                if (this.listener !== startedFor) { s?.release(); return@post }   // 그 사이 stop()된 경우
                signals = s
                if (s == null) log("AAOS 기어·주차 브레이크 신호를 쓸 수 없어요." + if (com.pitstop.shimpyo.BuildConfig.DEMO_TOOLS) " 시연 패널에서 주차 상태를 지정하세요." else "")
            }
        }
        emitParked()
    }

    override fun stop() {
        backrestMotion.cancel()
        runCatching { vehicle?.getOdometer()?.unregisterTripCurrentDrivingTime(tripListener) }
        passengerUndo.forEach { runCatching { it() } }
        passengerUndo.clear()
        signals?.release()
        signals = null
        runCatching { vehicle?.release() }
        vehicle = null
        listener = null
    }

    /** 시연·진단용: AAOS 신호가 없을 때 주차 상태를 직접 지정한다. null이면 자동. */
    fun overrideParked(value: Boolean?) {
        manualParked = value
        emitParked()
    }

    private fun emitParked() {
        val parked = manualParked ?: aaosParked
        // P단 확인을 잃으면 진행 중인 눕히기부터 멈춘다. 복귀는 컨트롤러가 이어서 요청한다.
        if (lastControlParked == true && parked != true) backrestMotion.cancel()
        lastControlParked = parked
        listener?.onParkedChanged(parked)
        if (parked == true && beltSeen) {
            beltSeen = false
            emitPassenger()
        }
    }

    /** 시연·진단용: 동승자 여부를 직접 지정한다. null이면 차량 신호를 따른다. */
    fun overridePassenger(value: Boolean?) {
        manualPassenger = value
        emitPassenger()
    }

    // ── 동승자(착좌 센서 → 안전벨트 경고) ────────────────────────────────────────

    /**
     * 운전석을 뺀 좌석의 착좌 신호를 구독한다. 한국 차 기준 운전석은 ROW_1_LEFT.
     * 여러 좌석을 한꺼번에 묶은 영역(운전석 포함)은 운전자와 구분할 수 없어 쓰지 않는다.
     */
    private fun registerPassengerSignals() {
        val v = vehicle ?: return
        passengerAttempts++
        try {
            val seat = v.getSeat()
            seat.checkSeatOccupancyCapabilities(
                { config, areas -> MainThread.post { subscribeOccupancy(config.accessType, areas) } },
                { e -> MainThread.post { retryPassenger(e) } },
            )
        } catch (t: Throwable) {
            retryPassenger(t)
        }
    }

    private fun retryPassenger(error: Throwable) {
        if (passengerAttempts >= 5) {
            log("착좌 센서 확인 실패(${error.message}). 안전벨트 경고로 대신해요.")
            registerSeatBeltFallback()
            return
        }
        MainThread.postDelayed(1_500L * passengerAttempts) { registerPassengerSignals() }
    }

    private fun subscribeOccupancy(access: AccessType, areas: List<SeatArea>) {
        val seat = runCatching { vehicle?.getSeat() }.getOrNull() ?: return
        val others = areas.filter { it.isPassengerSeat() }
        if (!access.canRead() || others.isEmpty()) {
            log("동승석 착좌 센서를 읽을 수 없어요. 안전벨트 경고로 대신해요.")
            registerSeatBeltFallback()
            return
        }
        occupancyReadable = true
        passengerSource = "착좌 센서 " + others.joinToString { it.key() }
        others.forEach { area ->
            val key = area.key()
            occupancy[key] = null
            val l = object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(value: Boolean?) {
                    MainThread.post { occupancy[key] = value; emitPassenger() }
                }

                override fun onFailed(e: Exception) {
                    MainThread.post { log("착좌 신호($key) 오류: ${e.message}") }
                }
            }
            try {
                seat.registerSeatOccupancy(area, l)
                passengerUndo += { seat.unregisterSeatOccupancy(area, l) }
            } catch (t: Throwable) {
                occupancy.remove(key)
                log("착좌 신호($key) 구독 실패: ${t.message}")
            }
        }
        log("동승자 착좌 센서 구독: ${occupancy.keys.joinToString()}")
        emitPassenger()
    }

    /**
     * 보조 신호: 동승석 안전벨트 미착용 경고. 경고는 사람이 앉아 있는데 벨트를 안 맸을 때 켜지므로
     * 켜졌다면 동승자가 있는 것이다. 꺼져 있는 것만으로는 ‘빈자리’인지 ‘벨트를 맨 동승자’인지 알 수 없어서,
     * 한 번 본 경고는 주차할 때까지 기억한다.
     */
    private fun registerSeatBeltFallback() {
        val v = vehicle ?: return
        try {
            val safety = v.getSafety()
            safety.checkSeatNotBuckledWarningCapabilities(
                { config, areas ->
                    MainThread.post {
                        val others = areas.filter { it.isPassengerSeat() }
                        if (!config.accessType.canRead() || others.isEmpty()) {
                            passengerSource = "없음(착좌·벨트 신호 모두 읽을 수 없음)"
                            log("동승자 신호를 쓸 수 없는 차예요. 시연 패널에서 지정할 수 있어요.")
                            return@post
                        }
                        beltReadable = true
                        passengerSource = "안전벨트 미착용 경고(보조) " + others.joinToString { it.key() }
                        others.forEach { area ->
                            val l = object : SeatBeltNotBuckledWarningListener {
                                override fun onSeatBeltNotBuckledWarningUpdated(status: Boolean?) {
                                    MainThread.post {
                                        if (status == true && !beltSeen) {
                                            beltSeen = true
                                            emitPassenger()
                                        }
                                    }
                                }

                                override fun onFailed(e: Exception) {
                                    MainThread.post { log("안전벨트 경고(${area.key()}) 오류: ${e.message}") }
                                }
                            }
                            runCatching {
                                safety.registerSeatBeltNotBuckledWarning(area, l)
                                passengerUndo += { safety.unregisterSeatBeltNotBuckledWarning(area, l) }
                            }.onFailure { log("안전벨트 경고 구독 실패: ${it.message}") }
                        }
                    }
                },
                { e -> MainThread.post { passengerSource = "없음(벨트 경고 확인 실패)"; log("안전벨트 경고 확인 실패: ${e.message}") } },
            )
        } catch (t: Throwable) {
            passengerSource = "없음(벨트 경고 API 없음)"
            log("안전벨트 경고를 쓸 수 없어요: ${t.message}")
        }
    }

    /** 착좌 센서: 한 자리라도 사람이 있으면 true, 모든 자리가 비었으면 false, 그 밖에는 null. */
    private fun passengerValue(): Boolean? {
        manualPassenger?.let { return it }
        if (occupancyReadable) {
            val values = occupancy.values
            return when {
                values.any { it == true } -> true
                values.isNotEmpty() && values.all { it == false } -> false
                else -> null
            }
        }
        if (beltReadable) return if (beltSeen) true else null
        return null
    }

    private fun emitPassenger() {
        val value = passengerValue()
        if (passengerEmitted && value == lastPassenger) return
        passengerEmitted = true
        lastPassenger = value
        listener?.onPassengerChanged(value)
    }

    private fun SeatArea.isPassengerSeat(): Boolean = values.isNotEmpty() && SeatValue.ROW_1_LEFT !in values
    private fun SeatArea.key(): String = values.joinToString("+") { it.name }
    private fun AccessType.canRead(): Boolean = this == AccessType.READ || this == AccessType.READ_WRITE

    // ── 주행 시간 ────────────────────────────────────────────────────────────

    private fun registerTripTime() {
        val v = vehicle ?: return
        tripAttempts++
        try {
            val odometer = v.getOdometer()
            runCatching { odometer.unregisterTripCurrentDrivingTime(tripListener) }
            odometer.registerTripCurrentDrivingTime(tripListener)
        } catch (t: Throwable) {
            retryTripTime(t)
        }
    }

    /** Vehicle.initialize()는 비동기로 서비스에 붙기 때문에 첫 등록이 실패할 수 있어 몇 번 다시 시도한다. */
    private fun retryTripTime(error: Throwable) {
        if (tripAttempts >= 5) {
            log("주행 시간 구독 실패: ${error.message}")
            return
        }
        MainThread.postDelayed(1_500L * tripAttempts) { registerTripTime() }
    }

    // ── 제어 ────────────────────────────────────────────────────────────────

    override fun setFreshAirBoost(enable: Boolean, done: (ControlResult) -> Unit) {
        val hvac = domain { it.getHVAC() }
        if (enable) {
            holdHvacPower("fresh") {
                change(hvac, "내기 순환 끄기", RECIRCULATION, key = "recirc", compute = { _, _ -> false }) { r1 ->
                    change(hvac, "송풍 강하게", FAN, key = "fan.fresh", compute = { b, _ ->
                        b?.let { (it.min + (it.max - it.min) * 0.75).roundToInt() }
                    }) { r2 -> done(merge(r1, r2)) }
                }
            }
        } else {
            restore(hvac, "내기 순환", RECIRCULATION, "recirc") { r1 ->
                restore(hvac, "송풍", FAN, "fan.fresh") { r2 ->
                    releaseHvacPower("fresh") { done(merge(r1, r2)) }
                }
            }
        }
    }

    override fun setRestClimate(enable: Boolean, celsius: Float, done: (ControlResult) -> Unit) {
        val hvac = domain { it.getHVAC() }
        if (enable) {
            holdHvacPower("rest") {
                change(hvac, "휴식 온도", TEMPERATURE, key = "temp", compute = { b, _ ->
                    if (b == null) celsius else celsius.toDouble().coerceIn(b.min, b.max).toFloat()
                }) { r1 ->
                    change(hvac, "송풍 약하게", FAN, key = "fan.rest", compute = { b, _ ->
                        b?.let { (it.min + 1).coerceAtMost(it.max).roundToInt() }
                    }) { r2 -> done(merge(r1, r2)) }
                }
            }
        } else {
            restore(hvac, "온도", TEMPERATURE, "temp") { r1 ->
                restore(hvac, "송풍", FAN, "fan.rest") { r2 ->
                    releaseHvacPower("rest") { done(merge(r1, r2)) }
                }
            }
        }
    }

    override fun setCabinTemperature(celsius: Float?, done: (ControlResult) -> Unit) {
        val hvac = domain { it.getHVAC() }
        if (celsius != null) {
            holdHvacPower("prep") {
                change(hvac, "출발 실내 온도", TEMPERATURE, key = "temp.prep", compute = { b, _ ->
                    if (b == null) celsius else celsius.toDouble().coerceIn(b.min, b.max).toFloat()
                }, done = done)
            }
        } else {
            restore(hvac, "출발 실내 온도", TEMPERATURE, "temp.prep") { r -> releaseHvacPower("prep") { done(r) } }
        }
    }

    // ── 공조 전원 ─────────────────────────────────────────────────────────────
    // 공조가 꺼져 있으면 송풍·온도를 차량이 거절한다(HVAC_FAN_SPEED error code 2 = 사용할 수 없음).
    // 외기·휴식 공조·출발 온도가 각각 전원을 ‘붙잡고’, 마지막 하나가 놓을 때 원래 상태(꺼짐)로 되돌린다.
    // 여러 기능이 겹쳐 켜고 꺼도 순서와 상관없이 원래대로 돌아간다.

    private val powerHolders = mutableSetOf<String>()
    /** 우리가 켜기 전의 공조 전원. null이면 모름(그때는 끄지 않는다). */
    private var powerWasOn: Boolean? = null
    private var powerArea: Any? = null

    private fun holdHvacPower(holder: String, next: () -> Unit) {
        // 차량 서비스가 다시 시작되는 등 콜백이 끝내 오지 않으면 휴식·깨우기 순서 전체가 멈춘다.
        // 그래서 한 번만 이어 가고, 시간이 지나면 전원 확인 없이 다음 단계로 넘어간다.
        var went = false
        val guard = MainThread.postDelayed(POWER_GUARD_MS) { if (!went) { went = true; log("공조 전원 응답 없음 → 그대로 진행"); next() } }
        val then: () -> Unit = { if (!went) { went = true; MainThread.cancel(guard); next() } }
        val hvac = domain { it.getHVAC() } ?: return then()
        val first = powerHolders.isEmpty()
        powerHolders += holder
        if (!first) return then()
        val setter = caller.findExact(hvac, "setHvacPower") ?: return then()
        val getter = caller.findExact(hvac, "getHvacPowerState")
        val check = caller.findExact(hvac, "checkHvacPowerCapabilities")

        fun turnOnIfOff() {
            if (getter == null) { powerWasOn = null; return then() }
            caller.invoke(hvac, getter, null, area = powerArea,
                onSuccess = { args ->
                    MainThread.post {
                        val on = args.firstNotNullOfOrNull { it as? Boolean }
                        powerWasOn = on
                        if (on != false) return@post then()
                        caller.invoke(hvac, setter, true, area = powerArea,
                            // 전원을 켠 직후에는 온도·송풍 속성이 아직 ‘사용할 수 없음’이라 잠깐 기다린다.
                            onSuccess = { MainThread.post { log("공조 켜기 → true"); MainThread.postDelayed(POWER_SETTLE_MS, then) } },
                            onFailure = { e -> MainThread.post { log("공조 켜기 실패: ${e.message}"); powerWasOn = null; then() } },
                        )
                    }
                },
                onFailure = { e -> MainThread.post { log("공조 전원 상태 읽기 실패: ${e.message}"); powerWasOn = null; then() } },
            )
        }

        if (check == null) return turnOnIfOff()
        caller.invoke(hvac, check, null,
            onSuccess = { args -> MainThread.post { powerArea = caller.parseCapability(args).area; turnOnIfOff() } },
            onFailure = { MainThread.post { turnOnIfOff() } },
        )
    }

    private fun releaseHvacPower(holder: String, next: () -> Unit) {
        var went = false
        val guard = MainThread.postDelayed(TIMEOUT_MS) { if (!went) { went = true; log("공조 끄기 응답 없음"); next() } }
        val then: () -> Unit = { if (!went) { went = true; MainThread.cancel(guard); next() } }
        if (!powerHolders.remove(holder) || powerHolders.isNotEmpty()) return then()
        val wasOn = powerWasOn
        powerWasOn = null
        val hvac = domain { it.getHVAC() }
        val setter = hvac?.let { caller.findExact(it, "setHvacPower") }
        // 우리가 켠 경우(원래 꺼짐)에만 끈다. 원래 켜져 있었거나 모르면 그대로 둔다.
        if (wasOn != false || hvac == null || setter == null) return then()
        caller.invoke(hvac, setter, false, area = powerArea,
            onSuccess = { MainThread.post { log("공조 원래대로 → 꺼짐"); then() } },
            onFailure = { e -> MainThread.post { log("공조 끄기 실패: ${e.message}"); then() } },
        )
    }

    override fun setDriverSeatRecline(recline: Boolean, done: (ControlResult) -> Unit) {
        if (recline && (manualParked ?: aaosParked) != true) return done(ControlResult.FAILED)
        val seat = domain { it.getSeat() }
        // Pleos Connect 에뮬레이터 2.1.0의 Seat에는 등받이 위치를 바로 정하는 set이 없고
        // moveSeatBackrestAngle(방향·속도)과 getCurrentSeatBackrestAnglePosition(현재 위치)만 있다.
        // 이때는 움직이게 한 뒤 위치를 보다가 목표에 닿으면 멈춘다(AAOS SEAT_BACKREST_ANGLE_1_MOVE/_POS와 같은 방식).
        val hasSetter = seat != null && SEAT_BACKREST.any { caller.find(seat, "set", it, listOf("Move")) != null }
        if (!hasSetter && seat != null && caller.findExact(seat, MOVE_BACKREST) != null) {
            backrestMotion.move(seat, recline, done)
            return
        }
        if (recline) {
            change(seat, "운전석 등받이", SEAT_BACKREST, exclude = listOf("Move"), key = "seat", canApply = { (manualParked ?: aaosParked) == true }, compute = seatTarget@{ b, current ->
                // 원래 위치를 모르면 나중에 세울 수 없으므로 눕히지 않는다.
                val now = (current as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: return@seatTarget null
                b?.let {
                    val target = it.min + (it.max - it.min) * 0.25
                    minOf(now, target).roundToInt()
                }
            }, done = done)
        } else {
            restore(seat, "운전석 등받이", SEAT_BACKREST, "seat", exclude = listOf("Move"), done = done)
        }
    }

    private val backrestMotion = BackrestMotion(
        caller, { (manualParked ?: aaosParked) == true }, MainThread::post,
        { delay, task ->
            val callback = MainThread.postDelayed(delay, task)
            val cancel: () -> Unit = { MainThread.cancel(callback) }
            cancel
        }, ::log,
    )

    override fun setCabinDim(dim: Boolean, done: (ControlResult) -> Unit) {
        val light = domain { it.getLight() }
        if (dim) {
            change(light, "실내 무드등 밝기", AMBIENT, key = "ambient", compute = { b, _ ->
                b?.let { (it.min + (it.max - it.min) * 0.2).roundToInt() }
            }, done = done)
        } else {
            restore(light, "실내 무드등 밝기", AMBIENT, "ambient", done = done)
        }
    }

    // 같은 "ambient" 키를 쓰므로 처음 바꾸기 전의 밝기만 기억되고, setCabinDim(false)가 그 값으로 되돌린다.
    override fun setWakeLight(fraction: Float, done: (ControlResult) -> Unit) {
        val light = domain { it.getLight() }
        val f = fraction.coerceIn(0f, 1f)
        change(light, "깨우는 빛", AMBIENT, key = "ambient", compute = { b, _ ->
            b?.let { (it.min + (it.max - it.min) * f).roundToInt() }
        }, done = done)
    }

    override fun setDriverWindowVent(open: Boolean, done: (ControlResult) -> Unit) {
        val window = domain { it.getWindow() }
        if (open) {
            if ((manualParked ?: aaosParked) != true) return done(ControlResult.FAILED)
            change(window, "운전석 창문", WINDOW, exclude = listOf("Move", "Lock"), key = "window", canApply = { (manualParked ?: aaosParked) == true }, compute = { b, _ ->
                b?.let { (maxOf(it.min, 0.0) + (it.max - maxOf(it.min, 0.0)) * 0.3).roundToInt().coerceAtLeast(1) }
            }, done = done)
        } else {
            // 원래 값을 모르면 닫힘(0)으로.
            restore(window, "운전석 창문", WINDOW, "window", exclude = listOf("Move", "Lock"), fallback = 0, done = done)
        }
    }

    // ── 진단 ────────────────────────────────────────────────────────────────

    /** SDK 진단 화면: 실제 SDK에서 찾은 메서드 이름. */
    fun diagnostics(): List<String> {
        val v = vehicle ?: return listOf("Vehicle SDK가 연결되지 않았어요.")
        val rows = mutableListOf<String>()
        fun section(title: String, target: Any?, keys: List<String>) {
            if (target == null) {
                rows += "[$title] 도메인 없음"; return
            }
            val names = caller.list(target, keys)
            rows += "[$title] " + if (names.isEmpty()) "관련 메서드 없음" else names.joinToString()
        }
        section("HVAC", runCatching { v.getHVAC() }.getOrNull(), listOf("Recirculation", "FanSpeed", "HvacTemperature"))
        section("Seat", runCatching { v.getSeat() }.getOrNull(), listOf("Backrest", "Recline"))
        section("Window", runCatching { v.getWindow() }.getOrNull(), listOf("WindowPosition"))
        section("Light", runCatching { v.getLight() }.getOrNull(), listOf("Ambient"))
        section("Seat 착좌", runCatching { v.getSeat() }.getOrNull(), listOf("Occupancy"))
        section("Safety 벨트", runCatching { v.getSafety() }.getOrNull(), listOf("SeatBelt", "Buckled"))
        val now = when (manualPassenger ?: passengerValue()) {
            true -> "있음"
            false -> "없음"
            null -> "알 수 없음"
        }
        rows += "[동승자] 신호: $passengerSource · 지금: $now" + (if (manualPassenger != null) " (시연 패널로 지정)" else "")
        // 어느 자리가 ‘있음’을 보내는지 보여 준다(에뮬레이터 Virtual Console의 착좌 설정과 맞춰 볼 때).
        if (occupancyReadable && occupancy.isNotEmpty()) {
            rows += "  자리별 착좌: " + occupancy.entries.joinToString { (k, v) ->
                "$k=" + when (v) { true -> "있음"; false -> "없음"; null -> "모름" }
            }
        }
        val picks = listOf(
            "공조 내기순환" to findSetter(runCatching { v.getHVAC() }.getOrNull(), RECIRCULATION),
            "공조 송풍" to findSetter(runCatching { v.getHVAC() }.getOrNull(), FAN),
            "공조 온도" to findSetter(runCatching { v.getHVAC() }.getOrNull(), TEMPERATURE),
            "운전석 등받이" to (
                findSetter(runCatching { v.getSeat() }.getOrNull(), SEAT_BACKREST, listOf("Move"))
                    ?: runCatching { v.getSeat() }.getOrNull()?.let { caller.findExact(it, MOVE_BACKREST)?.name?.let { n -> "$n (위치를 보며 이동)" } }
                ),
            "운전석 창문" to findSetter(runCatching { v.getWindow() }.getOrNull(), WINDOW, listOf("Move", "Lock")),
            "무드등 밝기" to findSetter(runCatching { v.getLight() }.getOrNull(), AMBIENT),
        )
        rows += ""
        rows += "쉼표가 사용할 메서드"
        picks.forEach { (label, name) -> rows += " · $label → ${name ?: "찾지 못함(음성 안내로 대체)"}" }
        return rows
    }

    private fun findSetter(target: Any?, keys: List<List<String>>, exclude: List<String> = emptyList()): String? =
        target?.let { t -> keys.firstNotNullOfOrNull { caller.find(t, "set", it, exclude) }?.name }

    // ── 내부 ────────────────────────────────────────────────────────────────

    private fun domain(get: (Vehicle) -> Any?): Any? = vehicle?.let { v -> runCatching { get(v) }.getOrNull() }

    /** Vehicle SDK의 영역 객체(SeatArea 등)인지. 콜백 인자에서 영역과 값을 가를 때 쓴다. */
    private fun isArea(v: Any): Boolean = v::class.qualifiedName?.contains(".model.area.") == true

    /**
     * 값 바꾸기: capability 확인 → 현재 값 저장 → 새 값 설정.
     * [compute]가 null을 돌려주면(범위를 모름) 바꾸지 않고 UNSUPPORTED.
     */
    private fun change(
        target: Any?,
        label: String,
        keys: List<List<String>>,
        exclude: List<String> = emptyList(),
        key: String,
        canApply: () -> Boolean = { true },
        compute: (ReflectiveVehicleCaller.Bounds?, Any?) -> Any?,
        done: (ControlResult) -> Unit,
    ) {
        var active = true
        val finish = once(label) { result -> active = false; done(result) }
        if (target == null) return finish(ControlResult.UNSUPPORTED)
        val setter = keys.firstNotNullOfOrNull { caller.find(target, "set", it, exclude) }
            ?: return finish(ControlResult.UNSUPPORTED).also { log("$label: set 메서드를 찾지 못함") }
        val property = setter.name.removePrefix("set")
        val check = caller.findExact(target, "check${property}Capabilities")
        // Vehicle SDK 2.3.2의 공조는 getHvacTemperature(area, unit, onComplete, onFailed)처럼 ‘getCurrent’가 아니라 ‘get’이다.
        // 원래 값을 못 읽으면 휴식 뒤에 되돌리지 못하므로 두 이름을 모두 찾는다.
        val getter = caller.findExact(target, "getCurrent$property")
            ?: caller.findExact(target, "get$property")
            ?: caller.findExact(target, "get${property}State")

        fun applyValue(bounds: ReflectiveVehicleCaller.Bounds?, area: Any?, current: Any?) {
            if (!active) return
            if (!canApply()) return finish(ControlResult.FAILED)
            val value = compute(bounds, current) ?: return finish(ControlResult.UNSUPPORTED).also {
                log("$label: 필요한 범위·현재 값을 알 수 없어 바꾸지 않음")
            }
            saved.remember(key, current, area)
            caller.invoke(target, setter, value,
                onSuccess = { MainThread.post { log("$label → $value"); finish(ControlResult.DONE) } },
                onFailure = { e -> MainThread.post { log("$label 실패: ${e.message}"); finish(ControlResult.FAILED) } },
                area = area,
            )
        }

        fun readCurrent(bounds: ReflectiveVehicleCaller.Bounds?, area: Any?, retried: Boolean = false) {
            if (getter == null) {
                log("$label: 현재 값을 읽는 메서드가 없어 나중에 되돌릴 수 없음")
                return applyValue(bounds, area, null)
            }
            caller.invoke(target, getter, null,
                // 콜백이 (영역, 값) 순서로 올 수 있어서 영역이 아닌 첫 값을 현재 값으로 쓴다.
                onSuccess = { args -> MainThread.post { applyValue(bounds, area, args.firstOrNull { it != null && !isArea(it) }) } },
                onFailure = { e ->
                    MainThread.post {
                        // 공조를 막 켰을 때처럼 잠깐 ‘사용할 수 없음’일 수 있어 한 번 더 읽어 본다.
                        if (!retried) {
                            MainThread.postDelayed(READ_RETRY_MS) { readCurrent(bounds, area, retried = true) }
                        } else {
                            log("$label: 현재 값 읽기 실패(나중에 되돌릴 수 없음): ${e.message}")
                            applyValue(bounds, area, null)
                        }
                    }
                },
                area = area,
            )
        }

        if (check == null) return readCurrent(null, null)
        caller.invoke(target, check, null,
            onSuccess = { args ->
                MainThread.post {
                    val cap = caller.parseCapability(args)
                    if (!cap.writable) {
                        log("$label: 이 차량에서 쓰기를 지원하지 않음")
                        finish(ControlResult.UNSUPPORTED)
                    } else {
                        readCurrent(cap.bounds, cap.area)
                    }
                }
            },
            onFailure = { e -> MainThread.post { log("$label 지원 확인 실패: ${e.message}"); finish(ControlResult.UNSUPPORTED) } },
        )
    }

    /** 저장해 둔 원래 값으로 되돌린다. 저장값이 없으면 [fallback], 그것도 없으면 그대로 둔다. */
    private fun restore(
        target: Any?,
        label: String,
        keys: List<List<String>>,
        key: String,
        exclude: List<String> = emptyList(),
        fallback: Any? = null,
        done: (ControlResult) -> Unit,
    ) {
        val finish = once("$label 복귀", done)
        if (target == null) return finish(ControlResult.UNSUPPORTED)
        saved.restore(key, fallback, write = { original, area, completed ->
            val setter = keys.firstNotNullOfOrNull { caller.find(target, "set", it, exclude) }
            if (setter == null) {
                completed(ControlResult.UNSUPPORTED)
            } else {
                caller.invoke(target, setter, original,
                    onSuccess = { MainThread.post { log("$label 원래대로 → $original"); completed(ControlResult.DONE) } },
                    onFailure = { e -> MainThread.post { log("$label 복귀 실패: ${e.message}"); completed(ControlResult.FAILED) } },
                    area = area,
                )
            }
        }, done = finish)
    }

    /** 콜백이 오지 않을 때를 대비해 [TIMEOUT_MS] 뒤에는 FAILED로 끝낸다. 결과는 한 번만 전달. */
    private fun once(label: String, done: (ControlResult) -> Unit): (ControlResult) -> Unit {
        var delivered = false
        val timeout = MainThread.postDelayed(TIMEOUT_MS) {
            if (!delivered) {
                delivered = true
                log("$label: 응답 시간 초과")
                done(ControlResult.FAILED)
            }
        }
        return { result ->
            if (!delivered) {
                delivered = true
                MainThread.cancel(timeout)
                done(result)
            }
        }
    }

    private fun merge(a: ControlResult, b: ControlResult): ControlResult = when {
        a == ControlResult.DONE || b == ControlResult.DONE -> ControlResult.DONE
        a == ControlResult.FAILED || b == ControlResult.FAILED -> ControlResult.FAILED
        else -> ControlResult.UNSUPPORTED
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        MainThread.post { listener?.onLog(message) }
    }

    private companion object {
        const val TAG = "ShimpyoVehicle"
        const val TIMEOUT_MS = 5_000L

        // 속성 이름 후보(앞의 것부터 시도). HvacRecirculation·HvacFanSpeed·HvacTemperature는
        // Pleos Vehicle SDK 2.3.2의 check/register 메서드 이름에서 확인한 속성 이름이다.
        val RECIRCULATION = listOf(listOf("HvacRecirculation"), listOf("Recirculation"))
        val FAN = listOf(listOf("HvacFanSpeed"), listOf("FanSpeed"))
        val TEMPERATURE = listOf(listOf("HvacTemperature"))
        const val MOVE_BACKREST = "moveSeatBackrestAngle"
        /** 공조 전원을 켠 뒤 온도·송풍을 바꾸기까지 기다리는 시간. */
        const val POWER_SETTLE_MS = 1_000L
        /** 공조 전원 확인·켜기 전체를 기다리는 최대 시간(capability → 읽기 → 켜기 → 안정 대기). */
        const val POWER_GUARD_MS = 8_000L
        /** 현재 값 읽기가 실패했을 때 한 번 더 읽기까지. */
        const val READ_RETRY_MS = 700L
        val SEAT_BACKREST = listOf(listOf("Backrest", "Angle", "1"), listOf("Backrest", "Angle"), listOf("Backrest"), listOf("Recline"))
        val WINDOW = listOf(listOf("WindowPosition"))
        val AMBIENT = listOf(listOf("AmbientLightBrightness"))
    }
}
