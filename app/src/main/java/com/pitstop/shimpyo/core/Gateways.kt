package com.pitstop.shimpyo.core

/**
 * 컨트롤러가 바깥 세계(차량, 내비, 음성)와 이야기하는 통로.
 * 실제 구현은 Pleos SDK, AAOS Car API, 안드로이드 기본 기능, 시뮬레이터 중에서
 * 기기 환경에 맞게 골라 끼운다(platform 패키지).
 */

/** 차량 제어·상태. 모든 제어는 비동기이며 결과를 콜백으로 돌려준다. */
interface VehicleGateway {
    val backend: Backend

    fun start(listener: Listener)
    fun stop()

    /** 외기 유입으로 바꾸고 송풍을 잠깐 강하게(졸음 방지). [enable]=false면 원래대로. */
    fun setFreshAirBoost(enable: Boolean, done: (ControlResult) -> Unit)

    /** 휴식 모드 공조: 송풍을 낮추고 온도를 [celsius]로. [enable]=false면 원래대로. */
    fun setRestClimate(enable: Boolean, celsius: Float, done: (ControlResult) -> Unit)

    /** 운전석 등받이를 눕힌다/원위치. 눕히기는 주차 확인 중에만, 세우기는 언제든 요청한다. */
    fun setDriverSeatRecline(recline: Boolean, done: (ControlResult) -> Unit)

    /** 실내 무드등을 어둡게/원래대로. */
    fun setCabinDim(dim: Boolean, done: (ControlResult) -> Unit)

    /** 운전석 창문을 환기 위치로 연다/닫는다. 열기는 주차 확인 중에만, 닫기는 언제든 요청한다. */
    fun setDriverWindowVent(open: Boolean, done: (ControlResult) -> Unit)

    /**
     * 출발할 때 수면 기록에 맞춘 실내 온도. [celsius]=null이면 원래 온도로 되돌린다.
     * 휴식 공조([setRestClimate])와는 따로 원래 값을 기억한다.
     */
    fun setCabinTemperature(celsius: Float?, done: (ControlResult) -> Unit) = done(ControlResult.UNSUPPORTED)

    /**
     * 낮잠 캡슐의 ‘빛으로 깨우기’: 무드등을 최댓값의 [fraction](0~1)만큼 밝힌다.
     * 원래 밝기는 [setCabinDim]과 같이 기억하므로 `setCabinDim(false)`로 되돌린다.
     */
    fun setWakeLight(fraction: Float, done: (ControlResult) -> Unit) = done(ControlResult.UNSUPPORTED)

    interface Listener {
        /** 주차 여부. true=주차(P단, 기어 미지원 차량에서만 주차 브레이크로 대체), false=주행 가능, null=알 수 없음. */
        fun onParkedChanged(parked: Boolean?)

        /** 차량이 알려 주는 이번 주행 시간(분). 모르면 호출하지 않는다. */
        fun onTripDrivingMinutes(minutes: Int)

        /**
         * 운전석 말고 다른 자리에 사람이 있는지. true=동승자 있음, false=없음, null=알 수 없음.
         * 착좌 센서가 1순위이고, 착좌 센서가 없는 차에서는 안전벨트 미착용 경고를 보조로 쓴다.
         */
        fun onPassengerChanged(present: Boolean?) {}

        fun onLog(message: String)
    }
}

/** 웨어러블·헬스 앱이 기록한 지난밤 수면. */
data class SleepRecord(
    /** 지난 24시간 동안 잔 시간. */
    val sleepHours: Double,
    /** 일어난 시각(0~24시, 30분은 .5). */
    val wakeHour: Double,
    /** 어디서 받아 왔는지(화면·기록 표시용). */
    val sourceLabel: String,
)

/** 캘린더에 잡힌 오늘 운행 일정. */
data class PlannedDrive(
    val title: String,
    /** 예상 운전 시간(분). */
    val expectedMinutes: Int,
    /** 자정~새벽 6시에 걸치는 운행인지. */
    val night: Boolean,
)

/**
 * 시동을 걸 때 미리 받아 오는 출발 전 정보(‘무입력 출발’).
 *
 * 수면 기록이 있으면 출발 전 수면 질문을 건너뛰고 그 값으로 그날의 기준을 잡는다.
 * 일정이 있으면 장거리·심야 운행에 맞춰 쉬어 갈 시점을 미리 잡는다.
 *
 * 지금 앱에는 시연용 구현([com.pitstop.shimpyo.core.sim.SimPreDriveSource])만 있다.
 * 양산 단계에서는 스마트폰 쪽 앱이 Health Connect(삼성 헬스·갤럭시 워치 수면 기록)나
 * HealthKit(애플 건강·애플 워치), 캘린더를 읽어 운전자 동의를 받은 뒤 차로 넘겨주는 구조를 생각하고 있다.
 */
interface PreDriveSource {
    /** 지난밤 수면 기록. 없으면 null(그때는 음성으로 묻는다). */
    fun lastNightSleep(): SleepRecord?

    /** 오늘 운행 일정. 없으면 null. */
    fun plannedDrive(): PlannedDrive?

    object None : PreDriveSource {
        override fun lastNightSleep(): SleepRecord? = null
        override fun plannedDrive(): PlannedDrive? = null
    }
}

interface NaviGateway {
    val backend: Backend
    /** 연결이 없으면 경유지 추가·검색 화면 열기가 됐다고 안내하지 않는다. */
    val available: Boolean get() = true

    fun start(listener: Listener)
    fun stop()

    /** 쉼터를 첫 번째 경유지로 추가 요청. ok는 요청 전달 성공이며 실제 경로 반영의 확인은 아니다. */
    fun addRestStop(shelter: Shelter, done: (ok: Boolean, message: String?) -> Unit)

    /** 휴식을 마친 뒤 경유지를 지운다(원래 목적지로 복귀). */
    fun removeRestStop()

    /** 쉼터 데이터가 없을 때: 내비 장소 검색 화면을 열어 ‘졸음쉼터’를 찾게 한다. */
    fun openShelterSearch()

    interface Listener {
        fun onLocation(point: GeoPoint)
        fun onRestStopArrived(name: String?)
        fun onLog(message: String)
    }
}

interface SpeechGateway {
    val backend: Backend

    /**
     * 실제로 음성을 인식할 수 있는지. false면(마이크·인식 엔진 없음) 무응답을 졸음 신호로 세지 않는다.
     * 이때는 시연 패널의 응답 버튼으로 대답을 넣는다.
     */
    val canListen: Boolean

    fun start()
    fun stop()

    /** 말하기. 말이 끝나면(또는 실패하면) [onDone]. */
    fun speak(text: String, onDone: () -> Unit)

    /**
     * 듣기. 질문을 마친 직후 호출한다.
     * [onResult]의 latencyMs = 듣기 시작부터 운전자가 말하기 시작할 때까지(ms). 무응답이면 null.
     */
    fun listen(timeoutMs: Long, onResult: (text: String, latencyMs: Long?) -> Unit)

    fun cancel()
}

interface Clock {
    fun nowMs(): Long
    fun hourOfDay(): Int
}
