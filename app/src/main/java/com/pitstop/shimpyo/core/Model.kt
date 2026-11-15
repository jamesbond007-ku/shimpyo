package com.pitstop.shimpyo.core

/**
 * 「쉼표」의 핵심 모델. 이 패키지(core)는 안드로이드·Pleos SDK에 의존하지 않는 순수 Kotlin이라
 * JVM에서 바로 컴파일·테스트할 수 있다.
 */

/** 졸음 위험 단계. 단계가 오를수록 개입 강도를 높인다. */
enum class RiskLevel(val label: String) {
    NORMAL("정상"),
    CAUTION("주의"),
    WARNING("경고"),
    REST_ADVISED("휴식 권고"),
}

/** 앱이 지금 무엇을 하고 있는지. 화면 상단에 그대로 표시된다. */
enum class Phase(val label: String) {
    IDLE("운행 전"),
    MONITORING("운행 모니터링 중"),
    TALKING("대화로 깨우는 중"),
    OFFERING("쉼터 제안 중"),
    EN_ROUTE("쉼터로 이동 중"),
    WAITING_PARK("주차를 기다리는 중"),
    RESTING("15분 휴식 중"),
    WAKING("다시 출발 준비 중"),
}

/** 각 기능을 실제로 어떤 구현이 처리하고 있는지. 시연 때 심사위원에게 보여주기 위한 정보. */
enum class Backend(val label: String) {
    PLEOS("Pleos SDK"),
    AAOS("AAOS Car API"),
    ANDROID("Android 기본"),
    SIM("시뮬레이터"),
}

data class GeoPoint(val lat: Double, val lon: Double)

/** 차량 제어 요청 결과. UNSUPPORTED이면 음성 안내로 대신한다. */
enum class ControlResult { DONE, UNSUPPORTED, FAILED }

data class ShimpyoConfig(
    /** 시연 배속. 60이면 실제 1초를 주행 1분으로 계산한다. 실차에서는 1. */
    val timeScale: Int = 1,
    /** 휴식 시간(분). 도로공사 권고 “15분 휴식”에서 가져온 기본값. */
    val restMinutes: Int = 15,
    /** 질문 후 응답을 기다리는 최대 시간(실제 시간, ms). */
    val listenTimeoutMs: Long = 8_000,
    /** 주의 단계에서 대화를 다시 시작하는 간격(주행 분). */
    val talkIntervalMinutes: Int = 10,
    /** 쉼터 제안을 거절한 뒤 다시 제안하기까지의 간격(주행 분). */
    val offerCooldownMinutes: Int = 10,
    /** 재출발 전 창문을 열어 두는 시간(실제 초). */
    val ventSeconds: Int = 20,
    /** 대화 한 번에 주고받는 횟수. */
    val turnsPerTalk: Int = 3,
    /**
     * P단을 풀고 출발하면(주차 → 주행) ‘운행 시작’을 누르지 않아도 운행을 시작한다.
     * 졸음 앱은 운전자가 아무것도 하지 않아도 돌아야 한다. 실제 작동판은 켜고, 시연판은 발표 흐름을 위해 끈다.
     */
    val autoStartOnDrive: Boolean = false,
)

/** 같이하기 기본값. 설정은 주차 중에만 바꿀 수 있다. */
enum class TeamPreference(val label: String) {
    ASK("물어보기"),
    ALWAYS_TOGETHER("항상 같이"),
    ALWAYS_SOLO("항상 혼자"),
}

/** 이번 운행의 놀이 방식. */
enum class PlayMode {
    /** 아직 정하지 않음(동승자에게 물어보기 전). */
    UNDECIDED,
    /** 운전자 혼자. 동승자가 타고 있으면 조용한 감시 모드. */
    SOLO,
    /** 운전자와 동승자가 번갈아 답하는 팀 게임. */
    TEAM,
}

/** 같이하기 모드에서 지금 누구 차례인지. */
enum class Turn(val label: String) { DRIVER("운전자분"), PASSENGER("동승자분") }

data class LogLine(val atMs: Long, val text: String)

/** 화면에 그리는 상태 스냅샷. */
data class UiState(
    val phase: Phase = Phase.IDLE,
    val level: RiskLevel = RiskLevel.NORMAL,
    val score: Int = 0,
    val drivingMinutes: Int = 0,
    val restRemainingSec: Int = 0,
    val lastSpoken: String = "",
    val lastHeard: String = "",
    val lastLatencyMs: Long? = null,
    val baselineMs: Long? = null,
    val latencyRatio: Double? = null,
    val declinedOffers: Int = 0,
    val parked: Boolean? = null,
    val restStopName: String? = null,
    val shelterCount: Int = 0,
    val vehicleBackend: Backend = Backend.SIM,
    val naviBackend: Backend = Backend.SIM,
    val speechBackend: Backend = Backend.ANDROID,
    val listening: Boolean = false,
    val logs: List<LogLine> = emptyList(),
    /** 출발 전 수면 점검: 지난 24시간 수면 시간. */
    val sleepHours: Double? = null,
    /** 일어난 뒤 깨어 있은 시간(주행하면서 늘어난다). */
    val hoursAwake: Double? = null,
    /** 유효 시간 안의 KSS 자가평가(1~9). */
    val kss: Int? = null,
    val recentGraded: Int = 0,
    val recentWrong: Int = 0,
    /** 운전석 말고 다른 자리에 사람이 있는지. null=알 수 없음. */
    val passengerPresent: Boolean? = null,
    /** 동승자가 있어 말놀이·질문을 멈추고 조용히 지켜보는 중인지. */
    val quietMode: Boolean = false,
    /** 수면 기록을 어디서 받아 왔는지(예: “갤럭시 워치”). 음성으로 물었으면 null. */
    val sleepSource: String? = null,
    /** 수면 기록에 맞춰 출발할 때 실내를 얼마나 바꿨는지. 수면 기록이 없으면 null. */
    val cabinPrep: CabinPrep? = null,
    /** 낮잠 캡슐: 빛으로 깨우는 중이면 지금 단계. 아니면 null. */
    val wakeStep: NapCapsule.WakeStep? = null,
    /** 시연 도구로 넣었지만 아직 질문을 기다리는 대답. 없으면 null. */
    val pendingAnswer: String? = null,
    /** 지금 하고 있는 말놀이 이름(대화 중일 때만). */
    val talkTitle: String? = null,
    /** 캘린더 일정으로 미리 잡아 둔 휴식 제안 시점(연속 주행 분). 없으면 null. */
    val plannedRestAtMinute: Int? = null,
    val plannedDriveTitle: String? = null,
    val teamPreference: TeamPreference = TeamPreference.ASK,
    val playMode: PlayMode = PlayMode.UNDECIDED,
    /** 같이하기 놀이 중일 때 지금 차례. 아니면 null. */
    val turn: Turn? = null,
    /** 같이하기 팀 점수(맞힌 수 − 반칙). */
    val teamScore: Int = 0,
    val fouls: Int = 0,
    // 쉼터 미니맵
    /** 내비가 알려 준 마지막 현재 위치. 아직 못 받았으면 null. */
    val location: GeoPoint? = null,
    /** 최근 위치 두 점으로 계산한 진행 방향(0=북, 90=동). 모르면 null. */
    val headingDeg: Double? = null,
    /** 진행 방향 앞쪽 쉼터(가까운 순, 같은 이름은 하나만). */
    val aheadShelters: List<ShelterCandidate> = emptyList(),
    /** 지금 들를지 묻고 있는 쉼터. 쉼터 제안 중일 때만 값이 있다. */
    val offeredShelter: ShelterCandidate? = null,
    /** 경유지로 넣은 쉼터. [restStopName]과 함께 바뀐다. */
    val restStop: Shelter? = null,
)
