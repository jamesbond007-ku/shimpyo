package com.pitstop.shimpyo.core

import com.pitstop.shimpyo.core.talk.DistanceGuessGame
import com.pitstop.shimpyo.core.talk.KoreanText
import com.pitstop.shimpyo.core.talk.RegionQuizGame
import com.pitstop.shimpyo.core.talk.MemoryGame
import com.pitstop.shimpyo.core.talk.KssCheckGame
import com.pitstop.shimpyo.core.talk.SleepCheckGame
import com.pitstop.shimpyo.core.talk.TalkDeck
import com.pitstop.shimpyo.core.talk.TalkGame
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 「쉼표」의 두뇌. 알아채고 → 깨우고 → 안내하고 → 쉬게 하고 → 다시 출발시키는 흐름을 관리한다.
 *
 * 스레드 규칙: 모든 메서드와 게이트웨이 콜백은 같은 스레드(안드로이드 메인 스레드)에서 호출된다.
 * platform 패키지의 구현이 콜백을 메인 스레드로 옮겨서 넘겨준다.
 *
 * 안전 원칙
 *  - 등받이 눕히기·창문 열기는 주차(P단)가 확인될 때만 한다. 세우기·닫기는 언제든 한다(누운 채 운전하는 것이 더 위험하다).
 *  - 쉼터 근처가 아닌 곳에서 주차하면 휴식을 바로 시작하지 않고 “여기서 쉬어 갈까요?”라고 먼저 묻는다.
 *  - 차량을 움직이기 전에 먼저 음성으로 알린다.
 *  - 주행 중 화면 조작을 요구하지 않는다(모든 확인은 음성).
 *  - 지원하지 않는 차량 기능은 음성 안내로 대신한다.
 *
 * 동승자가 있으면 출발 전 수면 점검(10초 남짓)은 그대로 하고, “같이 하실래요?”라고 한 번 묻는다.
 *  - 같이하기(팀 게임): 운전자·동승자가 번갈아 답한다. 쉼표가 매번 차례를 불러 주고, 졸음 판단에는 운전자 차례 대답만 쓴다.
 *  - 혼자(조용한 감시 모드): 말놀이 없이 운행·수면 신호만 보다가 경고(50점) 이상이면 외기를 넣고 쉼터를 한 번 권한다.
 * 주행 중에도 “같이 할게”, “혼자 할게”로 바로 바꿀 수 있다. 착좌 센서가 없는 차에서도 말로 바꾸면 된다.
 */
class ShimpyoController(
    private val vehicle: VehicleGateway,
    private val navi: NaviGateway,
    private val speech: SpeechGateway,
    private val clock: Clock,
    config: ShimpyoConfig = ShimpyoConfig(),
    private val random: Random = Random.Default,
    /** 시동 시점에 받아 오는 수면 기록·운행 일정. 없으면 출발 전에 음성으로 묻는다. */
    private val preDrive: PreDriveSource = PreDriveSource.None,
    initialTeamPreference: TeamPreference = TeamPreference.ASK,
    private val onState: (UiState) -> Unit,
) {
    var config: ShimpyoConfig = config
        set(value) {
            // 배속을 바꿔도 지금까지의 주행 시간이 튀지 않도록 기준 시각을 다시 잡는다.
            val keptMinutes = appDrivingMinutes()
            field = value
            restEndMs = clock.nowMs() - keptMinutes * 60_000L / value.timeScale.coerceAtLeast(1)
            publish()
        }

    private var state = UiState(
        vehicleBackend = vehicle.backend,
        naviBackend = navi.backend,
        speechBackend = speech.backend,
    )
    private var shelters = ShelterDirectory(emptyList())
    private val tracker = RiskTracker()
    private val latency = LatencyBaseline()
    private var lastEvaluatedScore = -1
    private val logs = ArrayDeque<LogLine>()

    /** 운행을 시작·종료할 때마다 바뀐다. 이전 운행의 늦은 콜백을 무시하기 위한 값. */
    private var session = 0
    private var busy = false
    private var listenToken = 0
    /** 대화를 끊을 때마다 바뀐다. 끊기 전에 시작한 말하기의 완료 콜백이 뒤늦게 대화를 이어 가지 않게 한다. */
    private var speechEpoch = 0
    private var awaitingAnswer: ((String, Long?) -> Unit)? = null
    private var lastAnswerInjected = false

    private var restEndMs = 0L
    private var vehicleTripMinutes: Int? = null
    private val vehicleTrip = VehicleTripTime()
    private var vehicleTripAtRest = 0

    private var greeted = false
    private var talkCount = 0
    private val deck = TalkDeck(random)
    private var lastTalkAtMinute = Int.MIN_VALUE / 2
    private var nextOfferAtMinute = 0
    /** 마지막으로 쉼터를 권한 시각. KSS 9는 그 뒤에 새로 답했을 때만 쿨다운을 건너뛴다. */
    private var lastOfferMs: Long? = null
    /** 쉼터로 가거나 주차를 기다리기 시작한 주행 시간(분). 너무 오래 끝나지 않으면 안내를 접는다. */
    private var guideSinceMinute = 0
    /** 휴식·깨우기 순서의 세대. 휴식 취소·운행 종료·휴식 중 주행이면 올라가서, 아직 돌던 차량 제어를 멈추고 되돌린다. */
    private var restEpoch = 0
    private var freshAirOn = false
    private var candidate: ShelterCandidate? = null
    /** 경유지로 넣은 쉼터(미니맵 표시용). 화면에는 state.restStopName이 있을 때만 내보낸다. */
    private var restStopShelter: Shelter? = null
    private var aheadShelters: List<ShelterCandidate> = emptyList()

    // 수면의학 근거 신호 (출발 전 수면 점검과 주행 중 KSS 자가평가로 얻는다)
    private var sleepHours: Double? = null
    private var awakeAtAsk: Double? = null
    private var awakeAskedMs = 0L
    private var kss: Int? = null
    private var kssAnsweredMs = 0L
    private var kssAskedMs: Long? = null
    /** 이번 운행에서 KSS 척도(1~9 설명)를 이미 한 번 들려줬는지. */
    private var kssExplained = false
    private val grades = ArrayDeque<Boolean>()
    /** 수면 기록을 받아 온 곳. 음성으로 물었으면 null. */
    private var sleepSource: String? = null
    /** 수면 기록으로 출발할 때 맞춘 실내. 수면 기록이 없으면 null. */
    private var cabinPrep: CabinPrep? = null
    /** 출발 실내 온도를 바꿔 둔 상태인지(휴식·운행 종료 때 되돌린다). */
    private var prepTempOn = false
    // 같이하기(팀 게임)
    /** 같이하기 기본값. 주차 중에만 [setTeamPreference]로 바꾼다. */
    var teamPreference: TeamPreference = initialTeamPreference
        private set
    private var playMode = PlayMode.UNDECIDED
    private var teamAsked = false
    /** 지금 대화가 차례를 나눠 하는 놀이인지. */
    private var teamGameActive = false
    private var turnIndex = 0
    private var currentTurn: Turn? = null
    private var teamScore = 0
    private var fouls = 0
    /** 운전자 차례에 동승자가 화면으로 답했는지. 그 차례의 반응 뒤에 알린다. */
    private var foulPending = false
    /** 놀이를 몇 번 시작했는지. 세 번에 한 번(1·4·7번째)은 차량 특화 놀이를 고른다. */
    private var gamesStarted = 0
    /** 지금 묻고 있는 말놀이(시연용 정답 넣기에 쓴다). */
    private var currentGame: TalkGame? = null
    private var nextVehicleIsRegion = true

    // 동승자·운행 일정
    private var passengerPresent: Boolean? = null
    private var plannedDrive: PlannedDrive? = null
    private var plannedRestAtMinute: Int? = null

    private var restStartMs = 0L
    /** 깨우기를 마무리하는 중(창문 닫기·원위치·출발 인사). 이때의 출발은 마무리 쪽에서 처리한다. */
    private var finishing = false
    /** 등받이를 세우는 제어가 진행 중인지, 안전장치가 몇 번 시도했는지. */
    private var seatRaising = false
    private var seatNetAttempts = 0
    private var seatNetLastMs = 0L

    /**
     * 안전장치: 휴식 단계 밖에서 주행 중인데 등받이가 눕혀져 있으면(안내 말이 끊기는 등으로 세우기가 빠졌을 때) 바로 세운다.
     * 실패하면 10초 간격으로 두 번 더 해 보고, 그래도 안 되면 직접 세우라고 한 번 알린다.
     */
    private fun seatSafetyNet() {
        if (!seatReclined) { seatNetAttempts = 0; return }
        if (parked == true || seatRaising || state.phase == Phase.RESTING || state.phase == Phase.WAKING) return
        if (seatNetAttempts >= 3 || (seatNetAttempts > 0 && clock.nowMs() - seatNetLastMs < 10_000L)) return
        seatNetAttempts++
        seatNetLastMs = clock.nowMs()
        seatRaising = true
        vehicle.setDriverSeatRecline(false) { r ->
            seatRaising = false
            seatReclined = r != ControlResult.DONE
            logControl("등받이 세우기(주행 중 안전장치)", r)
            if (seatReclined && seatNetAttempts >= 3) say("등받이를 세우지 못했어요. 안전한 곳에 차를 세우고 직접 세워 주세요.") {}
        }
    }

    /** 쉼터가 아닌 곳에서 주차했을 때 “여기서 쉬어 갈까요?”를 이번 주차에 이미 물었는지. 주차가 풀리면 지운다. */
    private var parkConfirmAsked = false
    private var seatReclined = false
    private var cabinDimmed = false
    private var restClimateOn = false
    private var windowOpen = false
    private var ventCloseAtMs = 0L
    /** 낮잠 캡슐: 지금까지 적용한 깨우기 단계. 휴식을 시작할 때 null. */
    private var wakeStep: NapCapsule.WakeStep? = null

    private var parked: Boolean? = null
    private var lastLocation: GeoPoint? = null
    private var headingAnchor: GeoPoint? = null
    private var headingDeg: Double? = null
    /** 최근에 바로 옆을 지난 쉼터의 노선(지금 달리는 고속도로로 본다)과 그때의 주행 분. */
    private var currentRoad: String? = null
    private var currentRoadAtMs = 0L
    /** 쉼터 짝 사이를 지날 때 알아낸 달리는 방향(반대 차로 쉼터를 고르지 않게)과 그때의 주행 분. */
    private var currentTravel: Travel? = null
    private var currentTravelAtMs = 0L
    /** 감시 단계에서 주차가 이어지기 시작한 시각. 오래 서 있으면 쉰 것으로 치거나(수동) 운행을 마친다(자동 시작). */
    private var parkedSinceMs: Long? = null

    // ── 연결 ────────────────────────────────────────────────────────────────

    fun attach() {
        vehicle.start(object : VehicleGateway.Listener {
            override fun onParkedChanged(parked: Boolean?) = handleParked(parked)
            override fun onTripDrivingMinutes(minutes: Int) {
                // 단위·되감김을 확인한 값만 쓴다(VehicleTripTime 참고).
                vehicleTrip.record(minutes, clock.nowMs())
                val before = vehicleTripMinutes
                val now = vehicleTrip.minutes()
                // 단위 확인이 끝나 차량 값을 처음 쓰게 되는 순간: 지금까지 앱 시계로 센 연속 주행을 그대로 이어 가도록 기준점을 잡는다.
                // (안 그러면 기준점이 0이라 그날 시동 이후 전체 주행 시간으로 한 번에 뛴다.)
                if (before == null && now != null && state.phase != Phase.IDLE) {
                    vehicleTripAtRest = now - appDrivingMinutes()
                }
                vehicleTripMinutes = now
            }
            override fun onPassengerChanged(present: Boolean?) = handlePassenger(present)
            override fun onLog(message: String) = log("차량: $message")
        })
        navi.start(object : NaviGateway.Listener {
            override fun onLocation(point: GeoPoint) = handleLocation(point)
            override fun onRestStopArrived(name: String?) = handleArrived(name)
            override fun onLog(message: String) = log("내비: $message")
        })
        speech.start()
        publish()
    }

    fun detach() {
        endTrip()
        vehicle.stop()
        navi.stop()
        speech.stop()
    }

    fun setShelters(directory: ShelterDirectory) {
        shelters = directory
        log(if (directory.size > 0) "졸음쉼터 ${directory.size}곳을 불러왔어요." else "쉼터 데이터가 없어 내비 검색으로 대신해요.")
        publish()
    }

    // ── 사용자 조작 ──────────────────────────────────────────────────────────

    fun startTrip() {
        if (state.phase != Phase.IDLE) return
        session++
        resetDrivingCounters()
        // 응답 속도 기준은 ‘오늘의 나’다. 다른 날·다른 운전자의 기준을 이어 쓰지 않는다.
        latency.reset()
        lastEvaluatedScore = -1
        greeted = false
        talkCount = 0
        sleepHours = null
        awakeAtAsk = null
        sleepSource = null
        cabinPrep = null
        playMode = initialPlayMode()
        teamAsked = false
        teamScore = 0
        fouls = 0
        foulPending = false
        gamesStarted = 0
        kss = null
        kssAskedMs = null
        kssExplained = false
        finishing = false
        // 지난 운행에서 알아낸 노선·방향은 버린다(돌아오는 길이면 정반대라서, 남겨 두면 맞는 쪽 쉼터를 걸러 낸다).
        currentRoad = null
        currentTravel = null
        parkedSinceMs = null
        loadPreDrive()
        state = state.copy(phase = Phase.MONITORING, declinedOffers = 0, restStopName = null)
        log("운행을 시작했어요.")
        publish()
    }

    fun endTrip() {
        if (state.phase == Phase.IDLE) return
        session++
        speech.cancel()
        awaitingAnswer = null
        pendingInjected = null
        busy = false
        restEpoch++
        restoreCabin()
        if (state.restStopName != null) navi.removeRestStop()
        state = state.copy(phase = Phase.IDLE, listening = false, restStopName = null, restRemainingSec = 0)
        log("운행을 종료했어요.")
        publish()
    }

    /** 화면의 ‘지금 쉬기’ 버튼. 주차 중이면 바로 휴식 모드, 아니면 주차를 기다린다. */
    fun requestRestNow() {
        if (state.phase == Phase.IDLE || state.phase == Phase.RESTING || state.phase == Phase.WAKING) return
        interruptConversation()
        if (parked == true) beginRest() else {
            setPhase(Phase.WAITING_PARK)
            say("차를 세우고 P단에 두면 ${config.restMinutes}분 휴식 모드를 시작할게요.") {}
        }
    }

    /** 휴식·경유를 취소하고 모니터링으로 돌아간다. */
    fun cancelRest() {
        val phase = state.phase
        if (phase !in setOf(Phase.EN_ROUTE, Phase.WAITING_PARK, Phase.RESTING, Phase.WAKING)) return
        interruptConversation()
        val restedMinutes = if (phase == Phase.RESTING || phase == Phase.WAKING) restElapsedMinutes() else 0
        restEpoch++
        restoreCabin()
        if (state.restStopName != null) navi.removeRestStop()
        state = state.copy(restStopName = null, restRemainingSec = 0)
        if (restedMinutes >= 5) resetDrivingCounters(afterRest = true)
        setPhase(Phase.MONITORING)
        val message = if (phase == Phase.RESTING || phase == Phase.WAKING) {
            "휴식을 마쳤어요. ${KssCheckGame.REST_HINT}"
        } else {
            "휴식을 취소했어요. ${KssCheckGame.REST_HINT}"
        }
        say(message) {}
    }

    /**
     * 시연용 응답 주입. 질문을 기다리는 중이면 [text]를 [latencyMs] 뒤에 대답한 것으로 처리한다.
     * latencyMs=null이면 무응답. 에뮬레이터에서 마이크 없이 시연할 때 쓴다.
     */
    fun injectResponse(text: String, latencyMs: Long?) {
        val answer = awaitingAnswer ?: run {
            // 질문을 말하는 중에 누르면 받아 두었다가 듣기가 시작되자마자 넣는다(시연에서 누를 때를 맞추지 않아도 되게).
            if (state.phase == Phase.IDLE) {
                log("운행을 시작한 뒤에 대답을 넣을 수 있어요.")
                return
            }
            pendingInjected = { text to latencyMs }
            state = state.copy(pendingAnswer = text.ifBlank { "(무응답)" })
            log("대답을 받아 뒀어요: ${text.ifBlank { "(무응답)" }} · 다음 질문이 끝나면 넣을게요")
            publish()
            return
        }
        lastAnswerInjected = true
        speech.cancel()
        answer(text, latencyMs)
    }

    /**
     * 시연용: 지금 말놀이 문제의 정답을 운전자가 말한 것처럼 넣는다(에뮬레이터는 마이크가 없어서).
     * 말놀이 중이 아니거나 정답이 없는 질문이면 “응”.
     */
    fun injectCorrectAnswer(latencyMs: Long = 1_500) {
        if (awaitingAnswer == null && state.phase != Phase.IDLE) {
            // 아직 문제를 말하는 중이면 정답은 듣기가 시작될 때 그 문제로 정한다.
            pendingInjected = { (currentGame?.takeIf { state.phase == Phase.TALKING }?.demoAnswer() ?: "응") to latencyMs }
            state = state.copy(pendingAnswer = "정답")
            log("대답을 받아 뒀어요: 정답 · 다음 질문이 끝나면 넣을게요")
            publish()
            return
        }
        val text = currentGame?.takeIf { state.phase == Phase.TALKING }?.demoAnswer() ?: "응"
        injectResponse(text, latencyMs)
    }

    /** 질문을 말하는 중에 받아 둔 시연용 대답. 듣기가 시작되면 바로 넣는다. */
    private var pendingInjected: (() -> Pair<String, Long?>)? = null

    /**
     * 시연 녹화용 자동 대답(디버그 빌드에서 adb로 켠다). 질문을 듣기 시작하고 [AUTO_ANSWER_MS] 뒤에
     * 같이하기 질문엔 “같이 할게”, 쉼터 제안엔 “응”, 말놀이엔 정답을 넣는다. 평소에는 꺼져 있다.
     */
    var demoAutoAnswer = false
    private var listenStartedMs = 0L
    private var lastPrompt = ""

    private fun autoAnswerIfDue() {
        if (!demoAutoAnswer || awaitingAnswer == null || clock.nowMs() - listenStartedMs < AUTO_ANSWER_MS) return
        when {
            lastPrompt.contains("같이 말놀이") -> injectResponse("같이 할게", 1_500)
            state.phase == Phase.OFFERING -> injectResponse("응, 쉬었다 가자", 1_200)
            else -> injectCorrectAnswer()
        }
    }

    // ── 주기 처리(1초마다) ─────────────────────────────────────────────────────

    fun tick() {
        seatSafetyNet()
        autoAnswerIfDue()
        when (state.phase) {
            Phase.IDLE -> Unit
            Phase.MONITORING -> {
                if (!busy && longParkHandled()) return
                updateRisk()
                if (!busy) decideNext()
            }
            Phase.TALKING, Phase.OFFERING -> updateRisk()
            Phase.EN_ROUTE -> {
                updateRisk()
                if (!busy && parked == true) {
                    // 쉼터에서 세웠으면 바로 쉬고, 다른 곳(정체·주유소 등)이면 먼저 묻는다. 한 번 주차에 한 번만.
                    if (atShelter()) beginRest() else if (!parkConfirmAsked) confirmRestHere()
                } else if (!busy) {
                    abandonGuideIfStale()
                }
            }
            Phase.WAITING_PARK -> {
                updateRisk()
                if (!busy && parked == true) beginRest() else if (!busy) abandonGuideIfStale()
            }
            Phase.RESTING -> restTick()
            Phase.WAKING -> when {
                // 깨우는 말을 하는 사이에 출발해도 누운 채 달리지 않게 한다.
                parked != true -> endRestByDriving(restElapsedMinutes())
                !busy && ventCloseAtMs > 0 && clock.nowMs() >= ventCloseAtMs -> finishWake()
            }
        }
        publish()
    }

    // ── 판단 ────────────────────────────────────────────────────────────────

    private fun decideNext() {
        val minute = drivingMinutes()
        if (!greeted) {
            greeted = true
            greet(passengerPresent == true)
            return
        }
        val level = state.level
        if (!isQuiet() && shouldAskKss(level)) {
            kssAskedMs = clock.nowMs()
            val intro = if (passengerPresent == true || isTeam()) "운전자분께 여쭤볼게요." else null
            // 척도 설명은 운행마다 처음 한 번만 길게 한다(수면 점검에서 이미 했으면 짧게).
            startTalk(KssCheckGame(::recordKss, brief = kssExplained), turns = 1, intro = intro)
            kssExplained = true
            return
        }
        // KSS 9(잠과 싸우는 중)는 쉬고 온 직후라도 바로 쉼터를 권한다.
        if (level >= RiskLevel.WARNING && (minute >= nextOfferAtMinute || kssUrgent())) {
            startOffer()
            return
        }
        // 동승자가 있는데 놀이 방식을 아직 안 정했으면 기본값대로 정하거나 한 번 묻는다.
        // (위의 쉼터 제안이 먼저다. 잠을 거의 못 잔 운전자에게는 놀이보다 휴식 권유가 우선이다.)
        if (passengerPresent == true && playMode == PlayMode.UNDECIDED && !teamAsked) {
            teamAsked = true
            when (teamPreference) {
                TeamPreference.ASK -> {
                    askTeam()
                    return
                }
                TeamPreference.ALWAYS_TOGETHER -> {
                    speakThenIdle(applyTeamIntent(KoreanText.TeamIntent.TOGETHER))
                    return
                }
                TeamPreference.ALWAYS_SOLO -> {
                    playMode = PlayMode.SOLO
                    log("같이하기 기본값: 항상 혼자 · 조용한 감시 모드")
                }
            }
        }
        // 캘린더 일정으로 미리 잡아 둔 휴식 시점. 한 운행에 한 번만 먼저 권하고, 그 뒤는 위험도로 판단한다.
        val planned = plannedRestAtMinute
        if (planned != null && minute >= planned && minute >= nextOfferAtMinute) {
            plannedRestAtMinute = null
            startOffer(planned = true)
            return
        }
        // 동승자가 있고 같이하기를 안 하면, 경고 단계 전에는 말을 걸지 않는다.
        if (isQuiet()) return
        if (level >= RiskLevel.CAUTION) {
            if (!freshAirOn) {
                freshAirOn = true
                // 말하는 동안·제어하는 동안 다음 대화가 끼어들지 않게 붙잡아 둔다(겹치면 음성 콜백이 엉켜 멈출 수 있다).
                busy = true
                val id = session
                say("잠깐 바깥 공기를 넣을게요.") {
                    vehicle.setFreshAirBoost(true) { r ->
                        logControl("외기 유입·송풍", r)
                        if (id == session) busy = false
                    }
                }
                return
            }
            val interval = if (state.declinedOffers > 0 || level >= RiskLevel.WARNING) {
                (config.talkIntervalMinutes / 2).coerceAtLeast(1)
            } else {
                config.talkIntervalMinutes
            }
            if (minute - lastTalkAtMinute >= interval) {
                val team = isTeam()
                gamesStarted++
                // 말놀이 8가지를 겹치지 않게 돌리고, 세 번에 한 번은 내비 위치로 만든 차량 특화 놀이를 넣는다.
                // 경고 단계면 정답률을 재는 집중 놀이를 먼저 고른다(차량 특화 놀이는 정답률에 넣지 않으므로 빼고).
                val vehicleTurn = level < RiskLevel.WARNING && gamesStarted % 3 == 1
                val game: TalkGame = (if (vehicleTurn) vehicleGame(team) else null)
                    ?: deck.next(needFocus = level >= RiskLevel.WARNING)
                // 단어 기억하기는 ‘듣기 → 사이 문제 → 떠올리기’ 세 차례가 한 판. 같이하기면 둘이 두 번씩 답하도록 한 차례 늘린다.
                startTalk(game, turns = turnsFor(game, team), intro = null, team = team)
            }
        }
    }

    /** 시연 도구에서 고를 수 있는 말놀이. */
    /** [gameTitle]은 그 놀이의 [TalkGame.title]. 지금 하는 놀이의 칩을 선택 상태로 보여 줄 때 쓴다. */
    enum class DemoGame(val label: String, val kind: TalkDeck.Kind?, val gameTitle: String) {
        WORD_CHAIN("끝말잇기", TalkDeck.Kind.WORD_CHAIN, "끝말잇기"),
        CHOSUNG("초성 퀴즈", TalkDeck.Kind.CHOSUNG, "초성 퀴즈"),
        OX("O/X 퀴즈", TalkDeck.Kind.OX, "O/X 퀴즈"),
        MEMORY("단어 기억하기", TalkDeck.Kind.MEMORY, "단어 기억하기"),
        REVERSE_DIGITS("숫자 거꾸로", TalkDeck.Kind.REVERSE_DIGITS, "숫자 거꾸로 말하기"),
        NONSENSE("넌센스", TalkDeck.Kind.NONSENSE, "넌센스 퀴즈"),
        ARITHMETIC("숫자 계산", TalkDeck.Kind.ARITHMETIC, "숫자 계산"),
        GUGUDAN("구구단", TalkDeck.Kind.GUGUDAN, "구구단"),
        REGION("지역 O/X", null, "지역 O/X"),
        DISTANCE("쉼터 거리 맞히기", null, "쉼터 거리 맞히기"),
    }

    /**
     * 시연용: 졸음 위험도와 상관없이 고른 말놀이를 지금 바로 시작한다.
     * 운행 전이면 인사·수면 점검 없이 운행을 시작하고 곧바로 말놀이로 들어간다. 하던 대화가 있으면 끊고 시작한다.
     * 지역 O/X·쉼터 거리 맞히기는 현재 위치가 있어야 한다(시연 도구 ‘가상 주행 → 쉼터 쪽으로 주행’).
     */
    fun demoStartGame(choice: DemoGame) {
        if (state.phase !in setOf(Phase.IDLE, Phase.MONITORING, Phase.TALKING)) {
            log("휴식·쉼터 안내 중에는 말놀이를 시작하지 않아요.")
            return
        }
        if (state.phase == Phase.IDLE) {
            startTrip()
            greeted = true
            log("시연: 인사·수면 점검 없이 말놀이부터 시작")
        }
        val team = isTeam()
        val game: TalkGame = when (choice) {
            DemoGame.REGION -> lastLocation?.let { here ->
                RegionQuizGame.build(shelters.nearby(here, maxKm = 40.0, limit = 3), shelters.shelters, random, count = if (team) 4 else 3)
            }
            DemoGame.DISTANCE -> lastLocation?.let { here -> headingDeg?.let { h -> DistanceGuessGame.build(shelters.aheadList(here, h, limit = 2, travel = travelNow())) } }
            else -> deck.create(choice.kind!!)
        } ?: run {
            log("${choice.label}: 현재 위치·진행 방향이 아직 없어요. 시연 도구에서 ‘쉼터 쪽으로 주행’을 먼저 눌러 주세요.")
            publish()
            return
        }
        interruptConversation()
        pendingInjected = null
        state = state.copy(pendingAnswer = null)
        gamesStarted++
        startTalk(game, turns = turnsFor(game, team), intro = null, team = team)
    }

    private fun turnsFor(game: TalkGame, team: Boolean): Int = when (game) {
        is MemoryGame -> 3
        is RegionQuizGame -> game.size
        is DistanceGuessGame -> game.size
        else -> config.turnsPerTalk + if (team) 1 else 0
    }

    private fun updateRisk() {
        val result = RiskEngine.evaluate(
            RiskEngine.Input(
                continuousDrivingMinutes = drivingMinutes(),
                hourOfDay = clock.hourOfDay(),
                latencyRatio = latency.ratio(),
                recentNoResponse = latency.lastNoResponse,
                noResponseStreak = latency.noResponseStreak,
                sleepHours = sleepHours,
                hoursAwake = currentHoursAwake(),
                kss = currentKss(),
                recentGraded = grades.size,
                recentWrong = grades.count { !it },
                teamMode = isTeam(),
            ),
        )
        // 단계를 내리는 히스테리시스는 ‘새 판단’이 나올 때만 센다. 1초마다 같은 값을 넣으면 3초 만에 경고가 풀린다.
        val level = if (result.level.ordinal >= tracker.level.ordinal || result.score != lastEvaluatedScore) {
            tracker.update(result.level)
        } else {
            tracker.level
        }
        lastEvaluatedScore = result.score
        if (level != state.level) {
            val why = result.reasons.joinToString().ifEmpty { "신호 안정" }
            log("위험도 ${state.level.label} → ${level.label} ($why)")
        }
        state = state.copy(
            level = level,
            score = result.score,
            baselineMs = latency.baselineMs,
            latencyRatio = latency.ratio(),
        )
    }

    // ── 대화 ────────────────────────────────────────────────────────────────

    private fun startTalk(game: TalkGame, turns: Int, intro: String?, team: Boolean = false) {
        busy = true
        teamGameActive = team
        turnIndex = 0
        talkCount++
        // 졸림 자가평가(KSS)는 깨우는 말놀이가 아니라 묻기만 하는 질문이라 말놀이 간격에 넣지 않는다.
        // (넣으면 주의 단계가 되자마자 KSS를 묻고, 첫 말놀이는 10분 뒤로 밀린다.)
        if (game !is KssCheckGame) lastTalkAtMinute = drivingMinutes()
        state = state.copy(talkTitle = game.title)
        setPhase(Phase.TALKING)
        log("대화 시작: ${game.title}")
        if (intro != null) say(intro) { runTurn(game, turns) } else runTurn(game, turns)
    }

    private fun runTurn(game: TalkGame, remaining: Int) {
        val turn = if (teamGameActive && isTeam()) (if (turnIndex % 2 == 0) Turn.DRIVER else Turn.PASSENGER) else null
        currentTurn = turn
        val prompt = game.nextPrompt()
        currentGame = game
        ask(if (turn != null) "${turn.label} 차례예요. $prompt" else prompt) { text, latencyMs ->
            // 졸음 판단에는 운전자 대답만 쓴다. 같이하기의 동승자 차례 대답은 속도·정답률 모두 빼고 팀 점수에만 쓴다.
            val driverAnswer = turn != Turn.PASSENGER
            if (driverAnswer) recordLatency(latencyMs, kindOf(game))
            // “쉬고 싶어”, “그만하고 쉬자”: 어느 질문의 대답이든 쉬자는 말이면 놀이를 멈추고 바로 쉼터를 경유지로 넣는다.
            if (KoreanText.wantsRest(text)) {
                // 수면 점검 중이었다면 KSS처럼 그 대답에 담긴 정보도 놓치지 않게 먼저 읽힌다(졸림 표현 → KSS).
                if (game is KssCheckGame || game is SleepCheckGame) KoreanText.parseKss(text)?.let(::recordKss)
                endTalk()
                restRequestedByVoice()
                return@ask
            }
            if (KoreanText.intentOf(text) == KoreanText.Intent.CANCEL) {
                updateRisk()
                say("알겠어요, 대화를 잠시 멈출게요.") { endTalk() }
                return@ask
            }
            // “같이 할게”, “혼자 할게”: 주행 중에도 바로 바꾼다. 수면 점검·졸림 질문의 대답에서는 읽지 않는다.
            val teamIntent = if (game is SleepCheckGame || game is KssCheckGame) null else KoreanText.teamIntentOf(text)
            teamIntent?.let { intent ->
                val wasTeam = isTeam()
                val message = applyTeamIntent(intent)
                updateRisk()
                when {
                    intent == KoreanText.TeamIntent.SOLO && (wasTeam || isQuiet()) -> say(message) { endTalk() }
                    else -> {
                        if (intent == KoreanText.TeamIntent.TOGETHER) {
                            // 방금은 운전자 차례였으니 다음은 동승자 차례로 이어 간다.
                            teamGameActive = true
                            turnIndex = 1
                        }
                        // 이번 문제는 답하지 않은 것으로 넘긴다(순서가 있는 놀이가 단계를 건너뛰지 않게).
                        game.skip()
                        say(message) { if (remaining > 1) runTurn(game, remaining - 1) else endTalk() }
                    }
                }
                return@ask
            }
            val heardSilence = latencyMs == null && (speech.canListen || lastAnswerInjected)
            // 마이크를 쓸 수 없어 아무것도 못 들은 경우. 졸음 신호도, 오답도 아니다.
            val noInput = !heardSilence && text.isBlank()
            val reaction = when {
                heardSilence -> {
                    game.skip()
                    if (turn == Turn.PASSENGER) "이번 문제는 넘어갈게요." else "대답이 없으시네요. 괜찮으세요?"
                }
                noInput -> {
                    game.skip()
                    "이번 문제는 넘어갈게요."
                }
                else -> game.react(text)
            }
            if (!heardSilence && !noInput) {
                val correct = game.lastCorrect
                if (driverAnswer && game.graded) correct?.let(::recordGrade)
                if (turn != null && correct == true) teamScore++
            }
            val foulNote = if (turn != null && foulPending) {
                foulPending = false
                teamScore--
                " 그리고 방금 운전자분 차례에 화면으로 답한 건 반칙이라, 팀 점수 1점 빼요."
            } else {
                ""
            }
            if (turn != null) turnIndex++
            // KSS처럼 위험도가 바뀌는 대답이 있었으면 바로 다시 계산한다.
            updateRisk()
            say(reaction + foulNote) {
                when {
                    // 수면 점검은 세 질문을 끝까지 마친 뒤에 판단한다(중간에 끊으면 기상 시각·KSS를 놓친다).
                    game !is SleepCheckGame && state.level >= RiskLevel.WARNING &&
                        (drivingMinutes() >= nextOfferAtMinute || kssUrgent()) -> {
                        endTalk()
                        startOffer()
                    }
                    // 대화 중에 동승자가 타고 같이하기를 안 하면 하던 놀이를 거기서 마친다.
                    // 수면 점검은 졸음 판단에 가장 중요한 정보라 동승자가 있어도 끝까지 묻는다.
                    game !is SleepCheckGame && isQuiet() -> endTalk()
                    remaining > 1 -> runTurn(game, remaining - 1)
                    else -> endTalk()
                }
            }
        }
    }

    private fun endTalk() {
        busy = false
        teamGameActive = false
        currentTurn = null
        currentGame = null
        state = state.copy(talkTitle = null)
        if (state.phase == Phase.TALKING) setPhase(Phase.MONITORING)
    }

    // ── 쉼터 제안 ────────────────────────────────────────────────────────────

    /**
     * 쉼터를 권한다.
     *  - 평소: “3킬로미터 앞에 ○○ 졸음쉼터가 있어요. 15분만 쉬었다 갈까요?”
     *  - [planned]: 캘린더 일정으로 미리 잡아 둔 휴식 시점.
     *  - 조용한 감시 모드: 외기를 먼저 넣고, 졸려 보인다는 말 대신 주행 시간만 짧게 말한다.
     */
    private fun startOffer(planned: Boolean = false) {
        lastOfferMs = clock.nowMs()
        busy = true
        setPhase(Phase.OFFERING)
        val quiet = isQuiet()
        val from = lastLocation
        candidate = if (from != null && shelters.size > 0) shelters.nearestAhead(from, headingDeg, preferRoad = roadNow(), travel = travelNow()) else null
        val c = candidate
        val where = c?.let {
            val name = it.shelter.spokenName
            // 도로 거리가 아니라 직선거리라서 그렇게 말한다(도로 거리는 미니맵의 경로 이름표에 나온다).
            "직선으로 약 ${formatKm(it.distanceKm)} 앞에 ${name}${KoreanText.subjectParticle(name)} 있어요."
        }
        val minutes = drivingMinutes()
        val lead = when {
            planned -> "계획한 휴식 시간이에요."
            // 조용한 모드는 ‘졸려 보인다’ 대신 사실만 짧게 말한다. 동승자 앞에서 운전자를 민망하게 하지 않기 위해서.
            quiet && minutes >= 60 -> "운전하신 지 ${formatDuration(minutes)} 됐어요."
            quiet -> "잠깐 쉬어 가면 좋을 때예요."
            else -> null
        }
        val prompt = when {
            where != null && lead != null -> "$lead $where ${config.restMinutes}분 쉬었다 갈까요?"
            where != null -> "$where ${config.restMinutes}분만 쉬었다 갈까요?"
            lead != null -> "$lead 가까운 졸음쉼터를 내비에서 찾아 드릴까요?"
            else -> "졸음이 오는 것 같아요. 가까운 졸음쉼터를 내비에서 찾아 드릴까요?"
        }
        if (quiet && !planned && !freshAirOn) {
            // 차량을 바꾸기 전에 먼저 말한다는 원칙은 조용한 모드에서도 지킨다. 대신 한 문장으로 짧게.
            freshAirOn = true
            say("바깥 공기를 조금 넣을게요.") {
                vehicle.setFreshAirBoost(true) { r -> logControl("외기 유입·송풍", r) }
                askYesNo(prompt, retried = false)
            }
            return
        }
        askYesNo(prompt, retried = false)
    }

    private fun askYesNo(prompt: String, retried: Boolean) {
        ask(prompt) { text, latencyMs ->
            recordLatency(latencyMs)
            // 마이크를 쓸 수 없어 아무것도 못 들었으면 거절이 아니다. 거절로 세지 않고 알린 뒤 나중에 다시 권한다.
            if (text.isBlank() && latencyMs == null && !speech.canListen && !lastAnswerInjected) {
                cannotHearOffer()
                return@ask
            }
            when (KoreanText.intentOf(text)) {
                KoreanText.Intent.YES -> acceptOffer()
                KoreanText.Intent.NO, KoreanText.Intent.CANCEL -> declineOffer()
                KoreanText.Intent.UNKNOWN -> when {
                    !retried -> askYesNo("쉬었다 갈까요? ‘응’ 또는 ‘아니’로 말해 주세요.", retried = true)
                    // 두 번 물어도 아무 말이 없으면 거절이 아니다. 졸음이 가장 위험한 경우라 더 짧게 다시 묻는다.
                    latencyMs == null && (speech.canListen || lastAnswerInjected) -> noAnswerToOffer()
                    else -> declineOffer()
                }
            }
        }
    }

    /** 쉼터 제안에 끝내 대답이 없을 때. 거절로 세지 않고, 외기를 넣고 [NO_ANSWER_RETRY_MINUTES]분 뒤에 다시 권한다. */
    private fun noAnswerToOffer() {
        nextOfferAtMinute = drivingMinutes() + NO_ANSWER_RETRY_MINUTES
        lastTalkAtMinute = Int.MIN_VALUE / 2
        log("쉼터 제안에 대답 없음 · ${NO_ANSWER_RETRY_MINUTES}분 뒤 다시 제안")
        val needAir = !freshAirOn
        val message = if (needAir) {
            "대답이 없으셔서 걱정돼요. 바깥 공기를 넣을게요. 조금 뒤에 다시 여쭤볼게요."
        } else {
            "대답이 없으셔서 걱정돼요. 졸리면 꼭 쉬어 가요. 조금 뒤에 다시 여쭤볼게요."
        }
        say(message) {
            if (needAir) {
                freshAirOn = true
                vehicle.setFreshAirBoost(true) { r -> logControl("외기 유입·송풍", r) }
            }
            setPhase(Phase.MONITORING)
            busy = false
        }
    }

    /** 운전자가 먼저 쉬자고 했을 때. 다시 묻지 않고 앞쪽 쉼터를 경유지로 넣는다(쉼터 데이터가 없으면 내비 검색). */
    private fun restRequestedByVoice() {
        log("운전자가 쉬자고 함")
        lastOfferMs = clock.nowMs()
        busy = true
        setPhase(Phase.OFFERING)
        val from = lastLocation
        candidate = if (from != null && shelters.size > 0) shelters.nearestAhead(from, headingDeg, preferRoad = roadNow(), travel = travelNow()) else null
        if (parked == true) {
            // 이미 서 있으면 경유지 없이 여기서 바로 쉰다.
            busy = false
            beginRest()
            return
        }
        say("좋아요, 쉬어 가요.") { acceptOffer() }
    }

    /** 마이크가 없어 쉼터 제안의 대답을 들을 수 없을 때. 거절 횟수는 그대로 두고, 거절 때와 같은 간격 뒤에 다시 알린다. */
    private fun cannotHearOffer() {
        nextOfferAtMinute = drivingMinutes() + config.offerCooldownMinutes
        log("쉼터 제안: 마이크를 쓸 수 없어 대답을 듣지 못함(거절로 세지 않음)")
        say("지금은 대답을 들을 수 없어요. 졸리면 가까운 쉼터에 꼭 들러 주세요. ${config.offerCooldownMinutes}분쯤 뒤에 다시 알려 드릴게요.") {
            setPhase(Phase.MONITORING)
            busy = false
        }
    }

    private fun acceptOffer() {
        if (!navi.available) {
            nextOfferAtMinute = drivingMinutes() + config.offerCooldownMinutes
            say("내비에 연결되지 않아 쉼터 안내를 시작하지 못했어요. 안전한 곳에 차를 세워 쉬어 주세요.") {
                setPhase(Phase.MONITORING)
                busy = false
            }
            return
        }
        val c = candidate
        if (c == null) {
            navi.openShelterSearch()
            say("내비에 졸음쉼터 검색을 요청할게요. 도착해서 P단에 두면 휴식 모드를 시작해요.") {
                setPhase(Phase.EN_ROUTE)
                busy = false
            }
            return
        }
        val id = session
        navi.addRestStop(c.shelter) { ok, message ->
            if (id != session) {
                // 그 사이 운행을 끝냈다. 늦게 들어간 경유지는 지운다.
                if (ok) navi.removeRestStop()
                return@addRestStop
            }
            if (ok) {
                restStopShelter = c.shelter
                state = state.copy(restStopName = c.shelter.name)
                say("${c.shelter.spokenName}${KoreanText.objectParticle(c.shelter.spokenName)} 경유지로 추가해 달라고 내비에 요청했어요. 내비 안내를 따라 안전한 곳에 주차해 주세요.") {
                    setPhase(Phase.EN_ROUTE)
                    busy = false
                }
            } else {
                log("경유지 추가 실패: ${message ?: "알 수 없음"}")
                navi.openShelterSearch()
                say("경유지를 넣지 못했어요. 내비에 졸음쉼터 검색을 요청할게요.") {
                    setPhase(Phase.EN_ROUTE)
                    busy = false
                }
            }
        }
    }

    private fun declineOffer() {
        state = state.copy(declinedOffers = state.declinedOffers + 1)
        nextOfferAtMinute = drivingMinutes() + config.offerCooldownMinutes
        lastTalkAtMinute = Int.MIN_VALUE / 2 // 바로 다음 판단에서 대화를 이어 간다
        val message = if (isQuiet()) {
            "알겠어요. ${config.offerCooldownMinutes}분쯤 뒤에 다시 여쭤볼게요."
        } else {
            "알겠어요. 대신 대화를 조금 더 자주 할게요. ${config.offerCooldownMinutes}분쯤 뒤에 다시 여쭤볼게요."
        }
        say(message) {
            setPhase(Phase.MONITORING)
            busy = false
        }
    }

    // ── 도착·휴식 ────────────────────────────────────────────────────────────

    private fun handleArrived(name: String?) {
        if (state.phase != Phase.EN_ROUTE) return
        if (parked == true) {
            beginRest()
            return
        }
        setPhase(Phase.WAITING_PARK)
        say("${name ?: "쉼터"}에 도착했어요. 차를 세우고 P단에 두면 ${config.restMinutes}분 휴식 모드를 시작할게요.") {}
    }

    private fun beginRest() {
        if (parked != true) {
            setPhase(Phase.WAITING_PARK)
            return
        }
        interruptConversation()
        busy = true
        // 이미 쉬었으니 캘린더로 잡아 둔 첫 휴식 제안은 지운다(그 뒤는 위험도로 판단).
        plannedRestAtMinute = null
        restStartMs = clock.nowMs()
        wakeStep = null
        setPhase(Phase.RESTING)
        log("낮잠 캡슐 시작: 끝나기 ${NapCapsule.WAKE_LEAD_MINUTES}분 전부터 빛으로 깨움")
        say(NapCapsule.restStartLine(config.restMinutes)) {
            applyRestComfort()
        }
    }

    /**
     * 낮잠 캡슐의 ‘빛으로 깨우기’ 한 단계. 말을 걸지 않고 무드등을 밝히고(아침빛 단계에서는 실내도 시원하게) 조용히 깨운다.
     * 원래 밝기·온도는 휴식을 시작할 때 이미 기억해 두었으므로 재출발 때 그대로 되돌아간다.
     */
    private fun applyWakeStep(step: NapCapsule.WakeStep) {
        wakeStep = step
        busy = true
        val id = session
        val steps = mutableListOf<(next: () -> Unit) -> Unit>()
        steps += { next ->
            vehicle.setWakeLight(step.light) { r ->
                // 휴식 때 조명을 못 낮췄어도 여기서 바꿨다면 재출발 때 되돌려야 한다.
                if (r == ControlResult.DONE) cabinDimmed = true
                logControl("깨우는 빛(${step.label} ${(step.light * 100).toInt()}%)", r); next()
            }
        }
        if (step.cool) steps += { next ->
            vehicle.setRestClimate(true, NapCapsule.WAKE_CELSIUS) { r ->
                if (r == ControlResult.DONE) restClimateOn = true
                logControl("깨우는 공조 ${NapCapsule.WAKE_CELSIUS.toInt()}도", r); next()
            }
        }
        runRestSequence(steps) { if (id == session) busy = false }
        publish()
    }

    private fun applyRestComfort() {
        val id = session
        val steps = mutableListOf<(next: () -> Unit) -> Unit>()
        if (freshAirOn) steps += { next ->
            vehicle.setFreshAirBoost(false) { r -> freshAirOn = false; logControl("외기 송풍 원래대로", r); next() }
        }
        // 출발 때 낮춰 둔 온도를 먼저 되돌려야 휴식 공조가 ‘원래 온도’를 바르게 기억한다.
        if (prepTempOn) steps += { next -> restorePrepTemperature(next) }
        steps += { next ->
            vehicle.setRestClimate(true, REST_CELSIUS) { r ->
                restClimateOn = r == ControlResult.DONE
                logControl("휴식 공조", r); next()
            }
        }
        steps += seat@{ next ->
            if (parked != true) { next(); return@seat }
            // 완료 전에 이미 움직일 수 있고, FAILED도 일부 이동 뒤의 실패일 수 있다.
            seatReclined = true
            vehicle.setDriverSeatRecline(true) { r ->
                seatReclined = r != ControlResult.UNSUPPORTED
                logControl("운전석 등받이 눕히기", r)
                if (r != ControlResult.DONE && parked == true && state.phase == Phase.RESTING) {
                    val message = if (r == ControlResult.UNSUPPORTED) {
                        "이 차는 시트 자동 조절을 지원하지 않아요. 등받이를 직접 눕혀 주세요."
                    } else {
                        "등받이 자동 조절을 완료하지 못했어요. 주차 상태를 확인하고 편한 자세로 쉬어 주세요."
                    }
                    say(message) { next() }
                } else next()
            }
        }
        steps += { next ->
            vehicle.setCabinDim(true) { r ->
                cabinDimmed = r == ControlResult.DONE
                logControl("실내 조명 낮추기", r); next()
            }
        }
        runRestSequence(steps) {
            if (id != session) return@runRestSequence
            // 안내 말과 시트·조명 조절이 끝난 때부터 낮잠 시간을 잰다(시연 배속에서 안내 10초가 10분이 되지 않게).
            restStartMs = clock.nowMs()
            busy = false
        }
    }

    private fun restTick() {
        if (parked != true) {
            endRestByDriving(restElapsedMinutes())
            return
        }
        val remaining = config.restMinutes * 60 - restElapsedSeconds()
        state = state.copy(restRemainingSec = remaining.coerceAtLeast(0))
        if (remaining <= 0 && !busy) {
            startWake()
            return
        }
        val step = NapCapsule.stepAt(remaining, config.restMinutes)
        if (step != null && !busy && (wakeStep == null || step.ordinal > wakeStep!!.ordinal)) applyWakeStep(step)
    }

    /**
     * 휴식(RESTING)이나 깨우기(WAKING) 중에 출발했을 때. 휴식을 마치고 감시로 돌아간다.
     * P단을 푼 직후라 차는 아직 서 있을 때가 많다. 누운 채 달리지 않도록 먼저 알리고 등받이를 바로 세운다.
     */
    private fun endRestByDriving(rested: Int) {
        // 휴식 중에 출발하면 휴식을 마치고 다시 졸음 감시로 돌아간다(RESTING에 머물면 감시·대화가 모두 멈춘다).
        // P단을 푼 직후라 차는 아직 서 있을 때가 많다. 누운 채 달리지 않도록 먼저 알리고 등받이를 바로 세운다.
        val lostSignal = parked == null
        log("${if (lostSignal) "휴식 중 주차 신호를 잃음" else "휴식 중 주행이 감지됨"} · ${rested}분 쉼")
        finishing = false
        restEpoch++
        interruptConversation()
        restoreClimateAndLight()
        closeWindowIfOpen()
        if (state.restStopName != null) navi.removeRestStop()
        state = state.copy(restStopName = null, restRemainingSec = 0)
        wakeStep = null
        // 실제로 쉰 시간이 짧으면 쉰 것으로 치지 않는다(주행 시간·졸음 신호를 그대로 이어 간다).
        if (rested >= 5) resetDrivingCounters(afterRest = true)
        setPhase(Phase.MONITORING)
        if (seatReclined) {
            busy = true
            val id = session
            val reason = if (lostSignal) "주차 상태를 확인할 수 없어 휴식을 중단해요." else "출발하시네요."
            say("$reason 등받이를 먼저 세울게요. 다 세워질 때까지 브레이크를 밟고 기다려 주세요.") {
                if (!seatReclined) { busy = false; return@say } // 그 사이 안전장치가 이미 세웠다
                seatRaising = true
                vehicle.setDriverSeatRecline(false) { r ->
                    seatRaising = false
                    seatReclined = r != ControlResult.DONE
                    logControl("등받이 세우기(출발 감지)", r)
                    if (id != session) return@setDriverSeatRecline
                    val after = if (seatReclined) "등받이를 세우지 못했어요. 출발 전에 꼭 직접 세워 주세요." else "등받이를 세웠어요. ${KssCheckGame.REST_HINT}"
                    say(after) { busy = false }
                }
            }
        } else {
            val reason = if (lostSignal) "주차 상태를 확인할 수 없어 휴식을 중단했어요." else "주행이 감지되어 휴식을 마쳤어요."
            say("$reason ${KssCheckGame.REST_HINT}") {}
        }
    }

    private fun startWake() {
        finishing = false
        busy = true
        setPhase(Phase.WAKING)
        state = state.copy(restRemainingSec = 0)
        val id = session
        val raiseSeat = seatReclined
        say(NapCapsule.wakeLine(config.restMinutes, raiseSeat, windowWillOpen = parked == true)) {
            val steps = mutableListOf<(next: () -> Unit) -> Unit>()
            // 눈을 뜬 뒤에 등받이를 세운다(목소리로 먼저 알린 다음). 세우기는 주차 여부와 상관없이 한다.
            if (raiseSeat) steps += { next ->
                vehicle.setDriverSeatRecline(false) { r -> seatReclined = r != ControlResult.DONE; logControl("등받이 세우기", r); next() }
            }
            // 창문 열기는 주차 중에만.
            if (parked == true) steps += window@{ next ->
                if (parked != true) { next(); return@window }
                windowOpen = true // 응답 전에 창문이 움직일 수 있다.
                vehicle.setDriverWindowVent(true) { r ->
                    windowOpen = r != ControlResult.UNSUPPORTED
                    logControl("창문 환기", r)
                    next()
                }
            }
            runRestSequence(steps) {
                if (id != session) return@runRestSequence
                ventCloseAtMs = clock.nowMs() + if (windowOpen) config.ventSeconds * 1000L else 0L
                busy = false
            }
        }
    }

    private fun finishWake() {
        finishing = true
        ventCloseAtMs = 0
        busy = true
        val id = session
        val epoch = restEpoch
        restoreCabin {
            if (id != session || epoch != restEpoch) return@restoreCabin
            if (state.restStopName != null) navi.removeRestStop()
            state = state.copy(restStopName = null)
            resetDrivingCounters(afterRest = true)
            wakeStep = null
            say(NapCapsule.DEPART_LINE) {
                setPhase(Phase.MONITORING)
                busy = false
                finishing = false
            }
        }
    }

    /** 휴식으로 바꾼 차량 상태를 되돌린다. 등받이 세우기·창문 닫기는 주차 여부와 관계없이 한다. */
    private fun restoreCabin(done: () -> Unit = {}) {
        val steps = mutableListOf<(next: () -> Unit) -> Unit>()
        // 창문 열기는 주차 중에만 하지만, 닫기는 주행 중에도 안전하므로 바로 닫는다.
        if (windowOpen) steps += { next ->
            vehicle.setDriverWindowVent(false) { r -> windowOpen = r != ControlResult.DONE; logControl("창문 닫기", r); next() }
        }
        // 등받이 세우기는 주행 중에도 한다(누운 채 운전하는 것이 더 위험하다).
        if (seatReclined) steps += { next ->
            vehicle.setDriverSeatRecline(false) { r -> seatReclined = r != ControlResult.DONE; logControl("운전석 원위치", r); next() }
        }
        steps += { next -> restoreClimateAndLight(); next() }
        runSequence(steps, done)
    }

    private fun restoreClimateAndLight() {
        if (cabinDimmed) vehicle.setCabinDim(false) { r -> cabinDimmed = r != ControlResult.DONE; logControl("실내 조명 원래대로", r) }
        if (restClimateOn) vehicle.setRestClimate(false, REST_CELSIUS) { r -> restClimateOn = r != ControlResult.DONE; logControl("공조 원래대로", r) }
        if (freshAirOn) vehicle.setFreshAirBoost(false) { r -> freshAirOn = false; logControl("외기 송풍 원래대로", r) }
        restorePrepTemperature()
    }

    /** 지금 위치가 경유지로 넣은 쉼터(또는 아무 졸음쉼터) 근처인지. 위치를 모르면 false(물어보는 쪽으로). */
    private fun atShelter(): Boolean {
        val here = lastLocation ?: return false
        restStopShelter?.let { if (Geo.distanceKm(here, it.point) <= AT_SHELTER_KM) return true }
        return shelters.size > 0 && shelters.nearby(here, maxKm = AT_SHELTER_KM, limit = 1).isNotEmpty()
    }

    /**
     * 쉼터로 가는 중에 쉼터가 아닌 곳에서 P단을 넣었을 때. 정체·주유소일 수 있으니 바로 시트를 눕히지 않고 묻는다.
     * ‘응’이면 여기서 쉬고, 그 밖의 대답이나 무응답이면 쉼터 안내를 이어 간다.
     */
    private fun confirmRestHere() {
        parkConfirmAsked = true
        busy = true
        log("쉼터가 아닌 곳에서 주차 · 여기서 쉴지 묻기")
        // 쉼터로 가는 동안은 내비가 앞에 나와 있어 마이크를 못 쓸 때가 많다. 그때는 묻지 않고, 차가 서 있으니 화면 버튼을 알려 준다.
        if (!speech.canListen) {
            say("여기서 쉬시려면 쉼표 화면에서 ‘지금 쉬기’를 눌러 주세요. 아니면 쉼터에 도착해서 차를 세우면 휴식을 시작할게요.") { busy = false }
            return
        }
        ask("여기서 ${config.restMinutes}분 쉬어 갈까요? 쉬시려면 ‘응’이라고 말해 주세요.") { text, _ ->
            // 묻는 사이 단계가 바뀌었으면(내비 도착 알림 등) 붙잡아 둔 것만 풀고 끝낸다. 안 풀면 다음 판단이 영영 멈춘다.
            if (state.phase != Phase.EN_ROUTE) { busy = false; return@ask }
            if (KoreanText.intentOf(text) == KoreanText.Intent.YES) {
                busy = false
                beginRest()
            } else {
                say("알겠어요. 쉼터에 도착해서 차를 세우면 휴식을 시작할게요.") { busy = false }
            }
        }
    }

    /**
     * 쉼터 안내를 받고도 [GUIDE_TIMEOUT_MINUTES]분 넘게 주행만 계속하면(출구를 지나쳤거나 검색 화면에서 고르지 않음)
     * 안내를 접고 감시로 돌아간다. 그래야 다시 대화하고, 필요하면 다음 쉼터를 권한다.
     */
    private fun abandonGuideIfStale() {
        if (parked == true || drivingMinutes() - guideSinceMinute < GUIDE_TIMEOUT_MINUTES) return
        log("쉼터 안내 ${GUIDE_TIMEOUT_MINUTES}분 동안 주차하지 않음 · 안내를 접음")
        if (state.restStopName != null) navi.removeRestStop()
        state = state.copy(restStopName = null)
        restStopShelter = null
        // 곧 다시 권할 수 있게 쿨다운을 짧게 둔다.
        nextOfferAtMinute = drivingMinutes() + GUIDE_REOFFER_MINUTES
        lastTalkAtMinute = Int.MIN_VALUE / 2
        busy = true
        say("쉼터에 들르지 않으셨네요. 안내는 접을게요. ${KssCheckGame.REST_HINT}") {
            setPhase(Phase.MONITORING)
            busy = false
        }
    }

    /**
     * 감시 단계에서 [LONG_PARK_MINUTES]분 넘게 주차해 있을 때. 휴식 모드를 쓰지 않고 쉬었거나 목적지에 도착한 것이다.
     *  - 자동 시작(실제 작동판): 운행을 마친다. 안 그러면 밤새 주차한 시간까지 ‘연속 주행’으로 세고 다음 날 그대로 이어 간다.
     *  - 수동 시작: 쉰 것으로 치고 연속 주행을 0으로 돌린다.
     * 운행을 마쳤으면 true.
     */
    private fun longParkHandled(): Boolean {
        if (parked != true) { parkedSinceMs = null; return false }
        val since = parkedSinceMs ?: clock.nowMs().also { parkedSinceMs = it }
        // 실제 시간으로 잰다. 시연 배속(1초=1분)에서 20초 세워 둔 것만으로 운행이 끝나거나 주행 시간이 0이 되면 안 된다.
        if (clock.nowMs() - since < LONG_PARK_MINUTES * 60_000L) return false
        parkedSinceMs = clock.nowMs()
        if (config.autoStartOnDrive) {
            log("${LONG_PARK_MINUTES}분 넘게 주차 · 운행을 마쳐요(다시 출발하면 새로 시작)")
            endTrip()
            return true
        }
        if (drivingMinutes() > 0) {
            log("${LONG_PARK_MINUTES}분 넘게 주차 · 쉰 것으로 보고 연속 주행을 다시 세요")
            resetDrivingCounters(afterRest = true)
        }
        return false
    }

    private fun handleParked(value: Boolean?) {
        if (parked == value) return
        val before = parked
        parked = value
        if (value != true) parkConfirmAsked = false
        // 출발 감지(주차가 아니던 상태 → 주행): 운행을 자동으로 시작한다. 앱을 켰을 때 이미 주행 중이어도 시작한다.
        if (config.autoStartOnDrive && value == false && before != false && state.phase == Phase.IDLE) {
            log("출발 감지 · 운행을 자동으로 시작해요")
            startTrip()
        }
        state = state.copy(parked = value)
        log(
            when (value) {
                true -> "주차 확인"
                false -> "주행 가능 상태"
                null -> "주차 상태 알 수 없음"
            },
        )
        // 휴식 중 주행으로 미뤄 둔 시트·창문 복귀를 주차되면 처리한다.
        if (value == true && state.phase !in setOf(Phase.RESTING, Phase.WAKING) && (seatReclined || windowOpen)) {
            restoreCabin()
        }
        publish()
    }

    private fun handleLocation(point: GeoPoint) {
        val previous = lastLocation
        lastLocation = point
        val anchor = headingAnchor
        if (anchor == null) {
            headingAnchor = point
        } else if (previous != null && Geo.distanceKm(previous, point) > TELEPORT_KM) {
            // 위치가 한 번에 크게 튀면(시연용 가상 주행을 켜고 끌 때, GPS를 처음 잡을 때) 두 점으로 방향을 재지 않는다.
            headingAnchor = point
            headingDeg = null
        } else if (Geo.distanceKm(anchor, point) >= 0.05) {
            headingDeg = Geo.bearingDeg(anchor, point)
            headingAnchor = point
        }
        // 쉼터 바로 옆(상·하행 어느 쪽이든)을 지나면 그 쉼터의 노선을 지금 달리는 도로로 기억한다.
        if (shelters.size > 0) {
            shelters.nearby(point, maxKm = ROAD_MATCH_KM, limit = 1).firstOrNull()?.shelter?.road?.takeIf { it.isNotBlank() }?.let {
                currentRoad = it
                currentRoadAtMs = clock.nowMs()
            }
            // 쉼터 짝(상·하행) 사이를 지나면 오른쪽 쉼터로 지금 달리는 방향을 안다.
            shelters.travelAt(point, headingDeg)?.let {
                if (it != currentTravel) log("달리는 방향: ${it.road} ${it.direction}")
                currentTravel = it
                currentTravelAtMs = clock.nowMs()
            }
        }
        // 미니맵에 그릴 앞쪽 쉼터. 위치가 바뀔 때만 다시 계산한다(253곳이라 가볍다).
        aheadShelters = if (shelters.size > 0) shelters.aheadList(point, headingDeg, limit = 3, maxKm = 60.0, travel = travelNow()) else emptyList()
        publish()
    }

    // ── 말하기·듣기 ──────────────────────────────────────────────────────────

    private fun say(text: String, onDone: () -> Unit) {
        val id = session
        val epoch = speechEpoch
        state = state.copy(lastSpoken = text)
        log("쉼표: $text")
        publish()
        speech.speak(text) { if (id == session && epoch == speechEpoch) onDone() }
    }

    private fun ask(prompt: String, onAnswer: (String, Long?) -> Unit) {
        say(prompt) {
            val token = ++listenToken
            val id = session
            val handler: (String, Long?) -> Unit = handler@{ text, latencyMs ->
                if (token != listenToken || awaitingAnswer == null || id != session) return@handler
                awaitingAnswer = null
                state = state.copy(listening = false, lastHeard = text.ifBlank { "(응답 없음)" }, lastLatencyMs = latencyMs)
                log("운전자: ${text.ifBlank { "(응답 없음)" }}" + (latencyMs?.let { " · %.1f초".format(Locale.ROOT, it / 1000.0) } ?: ""))
                onAnswer(text, latencyMs)
                publish()
            }
            awaitingAnswer = handler
            listenStartedMs = clock.nowMs()
            lastPrompt = prompt
            lastAnswerInjected = false
            state = state.copy(listening = true)
            publish()
            val queued = pendingInjected
            if (queued != null) {
                pendingInjected = null
                state = state.copy(pendingAnswer = null)
                val (text, latencyMs) = queued()
                lastAnswerInjected = true
                handler(text, latencyMs)
                return@say
            }
            speech.listen(config.listenTimeoutMs) { text, latencyMs -> handler(text, latencyMs) }
        }
    }

    /** 들은 대답이 진짜 음성(또는 시연 주입)일 때만 응답 지연으로 기록한다. */
    private fun recordLatency(latencyMs: Long?, kind: AnswerKind = AnswerKind.QUICK) {
        if (latencyMs == null && !speech.canListen && !lastAnswerInjected) return
        latency.record(latencyMs, kind)
    }

    /** 문제 난이도가 졸음처럼 보이지 않게, 생각해야 하는 문제는 대답 속도 기준을 따로 둔다. */
    private fun kindOf(game: TalkGame): AnswerKind = when (game) {
        is SleepCheckGame, is KssCheckGame, is RegionQuizGame, is com.pitstop.shimpyo.core.talk.OxQuizGame -> AnswerKind.QUICK
        else -> AnswerKind.THINK
    }

    private fun interruptConversation() {
        speechEpoch++
        if (awaitingAnswer != null || busy) {
            listenToken++
            awaitingAnswer = null
            speech.cancel()
            busy = false
            state = state.copy(listening = false)
        }
        teamGameActive = false
        currentTurn = null
        currentGame = null
        state = state.copy(talkTitle = null)
    }

    // ── 보조 ────────────────────────────────────────────────────────────────

    private fun runSequence(steps: List<(next: () -> Unit) -> Unit>, done: () -> Unit) {
        fun run(i: Int) {
            if (i >= steps.size) done() else steps[i] { run(i + 1) }
        }
        run(0)
    }

    /**
     * 휴식·깨우기 단계용 순서 실행기. 실행 중에 휴식이 취소되면(세대가 바뀌면) 남은 단계를 건너뛰고,
     * 취소 직후에 늦게 끝난 제어(예: 눕히던 등받이)까지 되돌린다.
     */
    private fun runRestSequence(steps: List<(next: () -> Unit) -> Unit>, done: () -> Unit) {
        val epoch = restEpoch
        fun run(i: Int) {
            if (epoch != restEpoch) {
                // 취소 뒤에 끝난 단계가 바꿔 놓은 것을 되돌린다. 차량 상태 표시(seatReclined 등)를 보고 필요한 것만.
                restoreCabin()
                return
            }
            if (i >= steps.size) done() else steps[i] { run(i + 1) }
        }
        run(0)
    }

    private fun closeWindowIfOpen() {
        if (windowOpen) vehicle.setDriverWindowVent(false) { r -> windowOpen = r != ControlResult.DONE; logControl("창문 닫기", r) }
    }

    // ── 출발 인사·무입력 출발·동승자 ─────────────────────────────────────────────

    /** 동승자가 있고 같이하기를 안 하는 중(조용한 감시 모드). */
    private fun isQuiet(): Boolean = passengerPresent == true && playMode != PlayMode.TEAM

    /**
     * 출발할 때 한 번. 수면 기록을 받아 왔으면 질문 없이 인사만 하고, 동승자가 있으면 질문을 미룬다.
     * 그 밖에는 수면 점검 세 가지(수면 시간·기상 시각·KSS)를 묻는다. 대답 속도 기준값도 여기서 잡힌다.
     */
    private fun greet(withPassenger: Boolean) {
        val known = sleepHours
        // 웨어러블 기록이 있으면 묻지 않고 그날 수면에 맞춰 실내를 미리 바꿔 둔다(졸음이 오기 전에).
        val prep = if (known != null) SleepPrep.plan(known, currentHoursAwake()) else null
        val calendarRest = plannedRestAtMinute
        prep?.firstRestMinute?.let { early -> plannedRestAtMinute = minOf(calendarRest ?: early, early) }
        val plan = planSentence()
        // 동승자가 있어도 수면 점검은 한다. 수면 시간이 졸음 판단에서 가장 중요한 정보이고, 세 질문은 10초 남짓이면 끝난다.
        val hello = if (withPassenger) "안녕하세요, 쉼표예요. 동승자분도 계시네요." else "안녕하세요, 쉼표예요."
        when {
            known != null && prep != null -> {
                cabinPrep = prep.prep
                // 캘린더 문장이 이미 휴식 시점을 말하면 실내 안내에서는 빼서 같은 말을 두 번 하지 않는다.
                val announce = SleepPrep.announcement(if (plan != null) prep.copy(firstRestMinute = null) else prep)
                busy = true
                say(
                    listOfNotNull(
                        "$hello ${sleepSource ?: "워치"} 기록으로 지난밤 ${formatHoursSpoken(known)} 주무신 걸 확인했어요.",
                        plan,
                        announce ?: "오늘도 안전하게 함께 갈게요.",
                    ).joinToString(" "),
                ) { applySleepPrep(prep) { busy = false } }
            }
            withPassenger -> startSleepCheck(
                listOfNotNull(hello, plan, "운전자분께 여쭤볼게요.").joinToString(" "),
            )
            else -> startSleepCheck(listOfNotNull(hello, plan, "오늘도 안전하게 함께 갈게요.").joinToString(" "))
        }
    }

    private fun startSleepCheck(intro: String) {
        kssAskedMs = clock.nowMs()
        kssExplained = true
        val check = SleepCheckGame(
            onSleepHours = { h -> sleepHours = h; log("수면 점검: 지난 24시간 ${"%.1f".format(h)}시간") },
            onWake = { w ->
                awakeAtAsk = KoreanText.hoursSince(w, clock.hourOfDay().toDouble())
                awakeAskedMs = clock.nowMs()
                log("수면 점검: 깨어 있은 지 약 ${awakeAtAsk!!.toInt()}시간")
            },
            onKss = ::recordKss,
        )
        startTalk(check, turns = 3, intro = intro)
    }

    /** 수면 기록에 맞춘 실내: 외기·송풍, 실내 온도. 지원하지 않는 차는 기록만 남기고 넘어간다. */
    private fun applySleepPrep(plan: SleepPrep.Plan, done: () -> Unit) {
        val id = session
        val steps = mutableListOf<(next: () -> Unit) -> Unit>()
        if (plan.freshAir && !freshAirOn) steps += { next ->
            freshAirOn = true
            vehicle.setFreshAirBoost(true) { r ->
                logControl("출발 외기 유입·송풍", r)
                if (id != session) { freshAirOn = false; vehicle.setFreshAirBoost(false) { r2 -> logControl("외기 송풍 원래대로", r2) } } else next()
            }
        }
        plan.celsius?.let { c ->
            steps += { next ->
                vehicle.setCabinTemperature(c) { r ->
                    prepTempOn = r == ControlResult.DONE
                    logControl("출발 실내 온도 ${c.toInt()}도", r)
                    // 그 사이 운행을 끝냈으면 바로 원래대로.
                    if (id != session) restorePrepTemperature() else next()
                }
            }
        }
        log("수면 기록에 맞춘 출발 실내: ${plan.prep.label}" + (plan.firstRestMinute?.let { " · 첫 휴식 ${it}분" } ?: ""))
        runSequence(steps) { if (id == session) done() }
    }

    private fun restorePrepTemperature(next: () -> Unit = {}) {
        if (!prepTempOn) return next()
        vehicle.setCabinTemperature(null) { r -> prepTempOn = r != ControlResult.DONE; logControl("출발 실내 온도 원래대로", r); next() }
    }

    private fun speakThenIdle(text: String) {
        busy = true
        say(text) { busy = false }
    }

    /**
     * 시동 시점에 웨어러블 수면 기록과 오늘 운행 일정을 받아 온다.
     * 수면 기록이 있으면 출발 전 수면 질문을 건너뛴다. 졸린 정도(KSS)는 ‘지금’의 상태라 웨어러블로 알 수 없어서
     * 주의 단계가 되면 평소처럼 따로 묻는다.
     */
    private fun loadPreDrive() {
        val sleep = runCatching { preDrive.lastNightSleep() }.getOrNull()
        if (sleep != null && sleep.sleepHours in 0.0..24.0 && sleep.wakeHour in 0.0..24.0) {
            sleepHours = sleep.sleepHours
            awakeAtAsk = KoreanText.hoursSince(KoreanText.ClockAnswer(sleep.wakeHour, explicit = true), clock.hourOfDay().toDouble())
            awakeAskedMs = clock.nowMs()
            sleepSource = sleep.sourceLabel
            log("${sleep.sourceLabel}: 지난 24시간 수면 ${"%.1f".format(sleep.sleepHours)}시간, 깨어 있은 지 약 ${awakeAtAsk!!.toInt()}시간")
        }
        val plan = runCatching { preDrive.plannedDrive() }.getOrNull()
        plannedDrive = plan
        plannedRestAtMinute = plan?.let { plannedRestMinute(it) }
        if (plan != null) {
            log("캘린더: ${plan.title} · 예상 ${plan.expectedMinutes}분" + (plannedRestAtMinute?.let { " → ${it}분쯤 휴식 제안" } ?: ""))
        }
    }

    /** 운전석 말고 다른 자리의 착좌 신호. 운행 중에 바뀌면 바로 모드를 바꾼다. */
    private fun handlePassenger(present: Boolean?) {
        if (passengerPresent == present) return
        val was = passengerPresent
        passengerPresent = present
        when {
            present == true -> {
                // 새로 탔으면 같이하기를 다시 정한다(기본값대로, 또는 한 번 묻기). 이미 같이하기면 그대로.
                if (playMode != PlayMode.TEAM) {
                    playMode = PlayMode.UNDECIDED
                    teamAsked = false
                }
                log("동승자 탑승 감지")
            }
            was == true -> {
                if (playMode == PlayMode.TEAM) log("동승자 하차 · 같이하기를 마쳐요") else log("동승자 하차 · 평소 모드")
                playMode = PlayMode.UNDECIDED
            }
            present == null -> log("동승자 여부를 알 수 없어요(착좌 신호 없음)")
        }
        publish()
    }

    // ── 같이하기(팀 게임) ─────────────────────────────────────────────────────

    private fun isTeam(): Boolean = playMode == PlayMode.TEAM

    /** ‘항상 같이’도 처음엔 정하지 않은 상태로 두고, 수면 점검 뒤에 규칙을 안내하면서 시작한다. */
    private fun initialPlayMode(): PlayMode = when {
        passengerPresent == true && teamPreference == TeamPreference.ALWAYS_SOLO -> PlayMode.SOLO
        else -> PlayMode.UNDECIDED
    }

    /**
     * 같이하기 기본값(물어보기·항상 같이·항상 혼자). 운전 중 화면 조작을 막기 위해 주차 중에만 바꿀 수 있다.
     * 바꿨으면 true.
     */
    fun setTeamPreference(preference: TeamPreference): Boolean {
        if (parked != true) {
            log("같이하기 기본값은 주차 중에만 바꿀 수 있어요.")
            publish()
            return false
        }
        teamPreference = preference
        log("같이하기 기본값: ${preference.label}")
        publish()
        return true
    }

    /** 동승자가 있을 때 한 번 묻는다. 대답은 운전자·동승자 누구든 받는다(졸음 판단에는 쓰지 않음). */
    private fun askTeam() {
        busy = true
        setPhase(Phase.TALKING)
        log("같이하기 여부 묻기")
        ask("동승자분도 같이 말놀이 하실래요? 운전자분과 번갈아 답하는 팀 게임이에요. ‘같이 할게’ 또는 ‘혼자 할게’라고 말해 주세요.") { text, _ ->
            val intent = KoreanText.teamIntentOf(text) ?: when (KoreanText.intentOf(text)) {
                KoreanText.Intent.YES -> KoreanText.TeamIntent.TOGETHER
                else -> KoreanText.TeamIntent.SOLO
            }
            val message = applyTeamIntent(intent)
            updateRisk()
            say(message) { endTalk() }
        }
    }

    /** 놀이 방식을 바꾸고 안내할 말을 돌려준다. */
    private fun applyTeamIntent(intent: KoreanText.TeamIntent): String {
        return when (intent) {
            KoreanText.TeamIntent.TOGETHER -> {
                val already = isTeam()
                playMode = PlayMode.TEAM
                if (!already) log("같이하기 시작")
                publish()
                if (already) {
                    "지금도 같이하고 있어요."
                } else {
                    "좋아요, 같이 해요. 제가 매번 누구 차례인지 불러 드릴게요. 남의 차례에 대답하면 반칙이에요. 동승자분은 화면을 눌러 답해도 돼요."
                }
            }
            KoreanText.TeamIntent.SOLO -> {
                val wasTeam = isTeam()
                playMode = PlayMode.SOLO
                teamGameActive = false
                if (wasTeam) log("같이하기 끝 · 혼자") else log("혼자 하기")
                publish()
                if (passengerPresent == true) {
                    "알겠어요. 대화 방해하지 않게 조용히 지켜볼게요. 같이 하고 싶으면 ‘같이 할게’라고 말해 주세요."
                } else {
                    "알겠어요, 운전자분하고만 할게요."
                }
            }
        }
    }

    /**
     * 같이하기에서 동승자가 화면으로 답할 때. 동승자 차례면 대답으로 받고,
     * 운전자 차례면 반칙으로 세고 그 대답은 쓰지 않는다(그 차례의 반응 뒤에 알린다).
     */
    fun passengerTouchAnswer(text: String) {
        val answer = awaitingAnswer
        val turn = currentTurn
        if (!isTeam() || answer == null || turn == null) {
            log("지금은 동승자 터치 답변을 받는 차례가 아니에요.")
            publish()
            return
        }
        if (turn == Turn.DRIVER) {
            if (!foulPending) {
                fouls++
                foulPending = true
                log("반칙: 운전자 차례에 동승자가 화면으로 답함")
            }
            publish()
            return
        }
        lastAnswerInjected = true
        speech.cancel()
        answer(text, TOUCH_LATENCY_MS)
    }

    // ── 차량 특화 놀이 ───────────────────────────────────────────────────────

    /** 내비 위치와 쉼터 데이터로 만든 놀이. 지역 O/X와 쉼터 거리 맞히기를 번갈아 낸다. 위치를 모르면 null. */
    private fun vehicleGame(team: Boolean): TalkGame? {
        val here = lastLocation ?: return null
        if (shelters.size == 0) return null
        val regionFirst = nextVehicleIsRegion
        nextVehicleIsRegion = !nextVehicleIsRegion
        val region = { RegionQuizGame.build(shelters.nearby(here, maxKm = 40.0, limit = 3), shelters.shelters, random, count = if (team) 4 else 3) }
        // 진행 방향을 아직 모르면 ‘앞쪽’ 쉼터를 고를 수 없으니 거리 맞히기는 내지 않는다.
        val distance = { headingDeg?.let { h -> DistanceGuessGame.build(shelters.aheadList(here, h, limit = 2, travel = travelNow())) } }
        return if (regionFirst) region() ?: distance() else distance() ?: region()
    }

    private fun planSentence(): String? {
        val plan = plannedDrive ?: return null
        val at = plannedRestAtMinute
        return when {
            at != null && plan.night ->
                "캘린더에 심야 ${plan.title} 일정이 있네요. 밤길이라 ${formatDuration(at)}쯤 지나면 한 번 쉬어 가자고 말씀드릴게요."
            at != null ->
                "캘린더에 ${formatDuration(plan.expectedMinutes)} 걸리는 ${plan.title} 일정이 있네요. ${formatDuration(at)}쯤 지나면 한 번 쉬어 가자고 말씀드릴게요."
            else -> null
        }
    }

    // ── 수면의학 근거 신호 ────────────────────────────────────────────────────

    private fun recordKss(value: Int) {
        kss = value
        kssAnsweredMs = clock.nowMs()
        // 수면 점검 안에서 답한 KSS도 ‘물어본 것’으로 친다. 안 그러면 점검이 끝나자마자 같은 질문을 또 한다.
        kssAskedMs = kssAnsweredMs
        log("졸림 자가평가 KSS $value/9")
    }

    private fun recordGrade(correct: Boolean) {
        grades.addLast(correct)
        while (grades.size > GRADE_WINDOW) grades.removeFirst()
    }

    /** 앱 시계 기준(시연 배속 반영) 경과 분. */
    private fun scaledMinutesSince(ms: Long): Long = (clock.nowMs() - ms) * config.timeScale / 60_000L

    /** KSS는 ‘지난 몇 분’의 상태라서 [KSS_VALID_MINUTES]가 지나면 쓰지 않고 다시 묻는다. */
    private fun currentKss(): Int? = kss?.takeIf { scaledMinutesSince(kssAnsweredMs) <= KSS_VALID_MINUTES }

    /**
     * KSS 9(잠과 싸우는 중)는 쉬고 온 직후라도 바로 쉼터를 권한다. 단, 그 대답이 마지막 제안 뒤에 나온 것일 때만.
     * (예전에는 거절한 뒤에도 KSS 9가 30분 동안 유효해서 몇 초마다 다시 권했다.)
     */
    private fun kssUrgent(): Boolean = currentKss() == 9 && lastOfferMs.let { it == null || kssAnsweredMs > it }

    private fun currentHoursAwake(): Double? = awakeAtAsk?.let { it + scaledMinutesSince(awakeAskedMs) / 60.0 }

    /** 주의 단계 이상이면 30분마다, 평소에는 60분마다 지금 졸린 정도를 묻는다. */
    private fun shouldAskKss(level: RiskLevel): Boolean {
        val asked = kssAskedMs ?: return level >= RiskLevel.CAUTION
        val age = scaledMinutesSince(asked)
        return (level >= RiskLevel.CAUTION && age >= KSS_VALID_MINUTES) || age >= 60
    }

    /**
     * 연속 주행 기준을 0으로 되돌린다. [afterRest]이면 쉬고 온 직후라서 [POST_REST_GRACE_MINUTES] 동안은
     * 쉼터를 다시 권하지 않는다. 수면 부족처럼 쉬어도 바로 사라지지 않는 신호 때문에 쉬자마자 또 권하는 것을 막는다.
     * (짧은 낮잠의 각성 효과는 한두 시간 이어진다는 보고가 있지만, 값은 기획 초기값이다.)
     */
    private fun resetDrivingCounters(afterRest: Boolean = false) {
        restEndMs = clock.nowMs()
        // 쉬고 나면 졸린 정도가 달라지므로 다시 묻는다. 수면 시간·기상 시각은 그대로 둔다.
        kss = null
        kssAskedMs = null
        grades.clear()
        vehicleTripAtRest = vehicleTripMinutes ?: 0
        tracker.reset()
        latency.clearRecent()
        lastTalkAtMinute = 0
        nextOfferAtMinute = if (afterRest) POST_REST_GRACE_MINUTES else 0
        state = state.copy(level = RiskLevel.NORMAL, score = 0)
    }

    private fun appDrivingMinutes(): Int =
        ((clock.nowMs() - restEndMs) * config.timeScale / 60_000L).toInt().coerceAtLeast(0)

    /** 연속 주행 시간(분). 실차에서는 차량이 알려 준 주행 시간을, 시연 배속 중에는 앱 시계를 쓴다. */
    fun drivingMinutes(): Int {
        val fromVehicle = vehicleTripMinutes
        // 휴식 기준점보다 작으면(기준점을 잡은 뒤 값이 바뀐 경우) 앱 시계로 계산한다.
        return if (config.timeScale == 1 && fromVehicle != null && fromVehicle >= vehicleTripAtRest) {
            fromVehicle - vehicleTripAtRest
        } else {
            appDrivingMinutes()
        }
    }

    private fun restElapsedSeconds(): Int =
        ((clock.nowMs() - restStartMs) * config.timeScale / 1000L).toInt()

    private fun restElapsedMinutes(): Int = restElapsedSeconds() / 60

    private fun setPhase(phase: Phase) {
        if (state.phase == phase) return
        val guiding = setOf(Phase.EN_ROUTE, Phase.WAITING_PARK)
        if (phase in guiding && state.phase !in guiding) guideSinceMinute = drivingMinutes()
        log("단계: ${phase.label}")
        state = state.copy(phase = phase)
        publish()
    }

    private fun logControl(what: String, result: ControlResult) {
        val text = when (result) {
            ControlResult.DONE -> "완료"
            ControlResult.UNSUPPORTED -> "이 차량에서 지원하지 않음"
            ControlResult.FAILED -> "실패"
        }
        log("$what: $text")
    }

    /** 기록 한 줄이 생길 때마다 받는 곳(앱에서는 logcat으로 내보내 현장 디버깅에 쓴다). */
    var logSink: ((String) -> Unit)? = null

    private fun log(text: String) {
        logSink?.invoke(text)
        logs.addLast(LogLine(clock.nowMs(), text))
        while (logs.size > MAX_LOGS) logs.removeFirst()
    }

    private fun publish() {
        state = state.copy(
            drivingMinutes = if (state.phase == Phase.IDLE) 0 else drivingMinutes(),
            shelterCount = shelters.size,
            // 음성 엔진은 비동기로 준비되므로 매번 현재 백엔드를 다시 읽는다.
            speechBackend = speech.backend,
            logs = logs.toList(),
            sleepHours = sleepHours,
            hoursAwake = currentHoursAwake(),
            kss = currentKss(),
            recentGraded = grades.size,
            recentWrong = grades.count { !it },
            passengerPresent = passengerPresent,
            quietMode = isQuiet(),
            sleepSource = sleepSource,
            cabinPrep = cabinPrep,
            plannedRestAtMinute = plannedRestAtMinute,
            plannedDriveTitle = plannedDrive?.title,
            teamPreference = teamPreference,
            playMode = playMode,
            turn = currentTurn,
            teamScore = teamScore,
            fouls = fouls,
            location = lastLocation,
            headingDeg = headingDeg,
            aheadShelters = aheadShelters,
            offeredShelter = if (state.phase == Phase.OFFERING) candidate else null,
            restStop = if (state.restStopName != null) restStopShelter else null,
            wakeStep = if (state.phase == Phase.RESTING || state.phase == Phase.WAKING) wakeStep else null,
        )
        onState(state)
    }

    /** 최근 [ROAD_VALID_MINUTES]분 안에 확인한 노선. 오래됐으면 다른 도로로 갈아탔을 수 있어 쓰지 않는다. */
    /** 최근 [ROAD_VALID_MINUTES]분 안에 알아낸 달리는 방향. 그 뒤 다른 노선으로 갈아탔으면 쓰지 않는다. */
    private fun travelNow(): Travel? = currentTravel?.takeIf {
        scaledMinutesSince(currentTravelAtMs) <= ROAD_VALID_MINUTES && (roadNow() == null || roadNow() == it.road)
    }

    private fun roadNow(): String? = currentRoad?.takeIf { scaledMinutesSince(currentRoadAtMs) <= ROAD_VALID_MINUTES }

    private fun formatKm(km: Double): String =
        if (km < 1.0) "${(km * 1000).toInt()}미터" else "${km.toInt()}킬로미터"

    /** 95 → “1시간 35분”, 40 → “40분”, 120 → “2시간”. */
    private fun formatDuration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "${m}분"
            m == 0 -> "${h}시간"
            else -> "${h}시간 ${m}분"
        }
    }

    /** 6.5 → “6시간 30분”, 7.0 → “7시간”. */
    private fun formatHoursSpoken(hours: Double): String = formatDuration((hours * 60).roundToInt())

    /** 테스트·디버그용 현재 상태. */
    fun snapshot(): UiState = state

    companion object {
        const val REST_CELSIUS = 22f
        private const val MAX_LOGS = 80
        /** 쉼터 제안에 끝내 대답이 없을 때 다시 묻기까지(주행 분). 거절 뒤 간격보다 짧다. */
        const val NO_ANSWER_RETRY_MINUTES = 3
        /** KSS 대답을 유효하게 보는 시간(분). KSS는 직전 몇 분의 상태를 묻는 척도다. */
        const val KSS_VALID_MINUTES = 30L
        /** 정답률을 볼 최근 채점 대답 수. */
        private const val GRADE_WINDOW = 5
        /** 휴식을 마친 뒤 쉼터를 다시 권하지 않는 주행 시간(분). */
        const val POST_REST_GRACE_MINUTES = 45
        /** 시연 자동 대답: 듣기 시작부터 대답을 넣기까지(실제 ms). */
        /** 쉼터 안내를 받고 이만큼(분) 주행해도 주차하지 않으면 안내를 접는다. */
        const val GUIDE_TIMEOUT_MINUTES = 20
        /** 안내를 접은 뒤 다시 권하기까지(분). */
        const val GUIDE_REOFFER_MINUTES = 5
        const val AUTO_ANSWER_MS = 1_500L
        /** 이보다 멀리 한 번에 옮겨 간 위치는 주행이 아니라 순간 이동으로 본다(km). 위치는 몇 초마다 들어온다. */
        const val TELEPORT_KM = 5.0
        /** 이 거리 안에서 주차하면 쉼터에 도착한 것으로 본다(km). 쉼터 길이가 수백 m라 여유를 둔다. */
        const val AT_SHELTER_KM = 0.6
        /** 감시 단계에서 이만큼(분) 주차가 이어지면 쉰 것(또는 운행 끝)으로 본다. 휴식 한 번(15분)보다 길게. */
        const val LONG_PARK_MINUTES = 20
        /** 쉼터에서 이만큼 안을 지나면 그 쉼터의 노선 위에 있다고 본다(km). */
        const val ROAD_MATCH_KM = 1.0
        /** 그렇게 알아낸 노선을 믿는 시간(주행 분). */
        const val ROAD_VALID_MINUTES = 30
        /** 화면 터치 대답은 말한 시간이 없으므로 임의 값을 넣는다(졸음 판단에는 쓰지 않음). */
        private const val TOUCH_LATENCY_MS = 1_000L

        /**
         * 캘린더 일정으로 첫 휴식을 권할 시점(연속 주행 분).
         * 도로공사 “2시간마다 휴식” 권고에 맞춰 쉼터까지 갈 여유를 두고 110분, 심야 운행은 90분(기획 초기값).
         * 예상 운전 시간이 그보다 짧으면 잡지 않는다.
         */
        fun plannedRestMinute(plan: PlannedDrive): Int? {
            val at = if (plan.night) 90 else 110
            return at.takeIf { plan.expectedMinutes > it }
        }
    }
}
