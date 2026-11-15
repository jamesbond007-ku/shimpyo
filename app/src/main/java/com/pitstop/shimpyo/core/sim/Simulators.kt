package com.pitstop.shimpyo.core.sim

import com.pitstop.shimpyo.core.Backend
import com.pitstop.shimpyo.core.ControlResult
import com.pitstop.shimpyo.core.GeoPoint
import com.pitstop.shimpyo.core.NaviGateway
import com.pitstop.shimpyo.core.PlannedDrive
import com.pitstop.shimpyo.core.PreDriveSource
import com.pitstop.shimpyo.core.Shelter
import com.pitstop.shimpyo.core.SleepRecord
import com.pitstop.shimpyo.core.VehicleGateway

/**
 * Pleos Vehicle Service가 없는 기기(일반 AVD, 발표장 노트북)에서 시연하기 위한 가상 차량.
 * 화면의 시연 패널에서 주차 상태를 바꾸고, 차량 제어 결과를 눈으로 확인할 수 있다.
 */
class SimVehicleGateway(
    /** 시트 제어를 지원하지 않는 차를 흉내 내려면 false. */
    var seatSupported: Boolean = true,
    /**
     * false면 차량 제어를 하는 척하지 않고 모두 UNSUPPORTED로 답한다.
     * 실제 작동판이 Pleos 차량 서비스를 못 찾았을 때 쓴다(가짜 ‘완료’ 대신 “직접 해 주세요” 안내가 나가게).
     */
    private val controlsWork: Boolean = true,
) : VehicleGateway {

    private fun noControl(done: (ControlResult) -> Unit): Boolean {
        if (controlsWork) return false
        done(ControlResult.UNSUPPORTED)
        return true
    }

    data class Cabin(
        val freshAirBoost: Boolean = false,
        val restClimate: Boolean = false,
        val seatReclined: Boolean = false,
        val cabinDim: Boolean = false,
        /** 낮잠 캡슐에서 깨우는 빛의 밝기(최댓값 대비). null이면 깨우는 중이 아님. */
        val wakeLight: Float? = null,
        /** 휴식 공조 온도(휴식 22도, 깨우는 동안 20도). */
        val restCelsius: Float? = null,
        val windowOpen: Boolean = false,
        /** 수면 기록에 맞춰 출발할 때 맞춘 실내 온도. null이면 원래 온도. */
        val prepCelsius: Float? = null,
        val parked: Boolean = false,
        val passenger: Boolean = false,
    )

    override val backend = Backend.SIM
    private var listener: VehicleGateway.Listener? = null

    var cabin = Cabin()
        private set

    /** 가상 차량 상태가 바뀔 때 화면을 갱신하기 위한 콜백. */
    var onCabinChanged: (Cabin) -> Unit = {}

    override fun start(listener: VehicleGateway.Listener) {
        this.listener = listener
        listener.onParkedChanged(cabin.parked)
        listener.onPassengerChanged(cabin.passenger)
    }

    override fun stop() {
        listener = null
    }

    fun setParked(parked: Boolean) {
        update(cabin.copy(parked = parked))
        listener?.onParkedChanged(parked)
    }

    /** 시연용: 조수석·뒷좌석에 사람이 앉은 것처럼 만든다(착좌 센서 흉내). */
    fun setPassenger(present: Boolean) {
        update(cabin.copy(passenger = present))
        listener?.onPassengerChanged(present)
    }

    override fun setFreshAirBoost(enable: Boolean, done: (ControlResult) -> Unit) {
        if (noControl(done)) return
        update(cabin.copy(freshAirBoost = enable))
        done(ControlResult.DONE)
    }

    override fun setRestClimate(enable: Boolean, celsius: Float, done: (ControlResult) -> Unit) {
        if (noControl(done)) return
        update(cabin.copy(restClimate = enable, restCelsius = if (enable) celsius else null))
        done(ControlResult.DONE)
    }

    override fun setWakeLight(fraction: Float, done: (ControlResult) -> Unit) {
        if (noControl(done)) return
        update(cabin.copy(cabinDim = true, wakeLight = fraction))
        done(ControlResult.DONE)
    }

    override fun setDriverSeatRecline(recline: Boolean, done: (ControlResult) -> Unit) {
        if (noControl(done)) return
        if (!seatSupported) {
            done(ControlResult.UNSUPPORTED)
            return
        }
        if (recline && !cabin.parked) {
            // 안전 원칙 검증용: 눕히기는 주차 중에만. 세우기는 언제든 받는다(누운 채 운전하는 것이 더 위험하다).
            listener?.onLog("주행 중 등받이 눕히기 요청을 거부함")
            done(ControlResult.FAILED)
            return
        }
        update(cabin.copy(seatReclined = recline))
        done(ControlResult.DONE)
    }

    override fun setCabinDim(dim: Boolean, done: (ControlResult) -> Unit) {
        if (noControl(done)) return
        update(cabin.copy(cabinDim = dim, wakeLight = null))
        done(ControlResult.DONE)
    }

    override fun setDriverWindowVent(open: Boolean, done: (ControlResult) -> Unit) {
        if (noControl(done)) return
        if (open && !cabin.parked) {
            listener?.onLog("주행 중 창문 열기 요청을 거부함")
            done(ControlResult.FAILED)
            return
        }
        update(cabin.copy(windowOpen = open))
        done(ControlResult.DONE)
    }

    override fun setCabinTemperature(celsius: Float?, done: (ControlResult) -> Unit) {
        if (noControl(done)) return
        update(cabin.copy(prepCelsius = celsius))
        done(ControlResult.DONE)
    }

    private fun update(next: Cabin) {
        cabin = next
        onCabinChanged(next)
    }
}

/** NaviHelper가 없는 기기용 가상 내비. 시연 패널에서 위치와 도착을 흉내 낸다. */
class SimNaviGateway(private val controlsWork: Boolean = true) : NaviGateway {
    override val available: Boolean get() = controlsWork
    override val backend = Backend.SIM
    private var listener: NaviGateway.Listener? = null

    var restStop: Shelter? = null
        private set
    var searchOpened: Boolean = false
        private set

    override fun start(listener: NaviGateway.Listener) {
        this.listener = listener
    }

    override fun stop() {
        listener = null
    }

    override fun addRestStop(shelter: Shelter, done: (ok: Boolean, message: String?) -> Unit) {
        if (!controlsWork) return done(false, "내비게이션에 연결되지 않음")
        restStop = shelter
        listener?.onLog("경유지 추가: ${shelter.name}")
        done(true, null)
    }

    override fun removeRestStop() {
        restStop?.let { listener?.onLog("경유지 삭제: ${it.name}") }
        restStop = null
    }

    override fun openShelterSearch() {
        if (!controlsWork) return
        searchOpened = true
        listener?.onLog("장소 검색 화면 열기: 졸음쉼터")
    }

    fun simulateLocation(point: GeoPoint) {
        listener?.onLocation(point)
    }

    fun simulateArrival() {
        listener?.onRestStopArrived(restStop?.name)
    }
}

/**
 * 시연용 출발 전 정보. 시연 패널에서 ‘워치 연동’·‘캘린더 일정’을 켜면 값이 들어간다.
 * 실제 헬스 앱·캘린더와 연결된 것은 아니다.
 */
class SimPreDriveSource : PreDriveSource {
    var sleep: SleepRecord? = null
    var plan: PlannedDrive? = null

    override fun lastNightSleep(): SleepRecord? = sleep
    override fun plannedDrive(): PlannedDrive? = plan
}
