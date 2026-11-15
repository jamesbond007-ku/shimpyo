package com.pitstop.shimpyo

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pitstop.shimpyo.core.Clock
import com.pitstop.shimpyo.core.Geo
import com.pitstop.shimpyo.core.GeoPoint
import com.pitstop.shimpyo.core.NaviGateway
import com.pitstop.shimpyo.core.Phase
import com.pitstop.shimpyo.core.PlannedDrive
import com.pitstop.shimpyo.core.Shelter
import com.pitstop.shimpyo.core.ShelterDirectory
import com.pitstop.shimpyo.core.ShimpyoConfig
import com.pitstop.shimpyo.core.ShimpyoController
import com.pitstop.shimpyo.core.SleepRecord
import com.pitstop.shimpyo.core.TeamPreference
import com.pitstop.shimpyo.core.UiState
import com.pitstop.shimpyo.core.VehicleGateway
import com.pitstop.shimpyo.core.sim.SimNaviGateway
import com.pitstop.shimpyo.core.sim.SimPreDriveSource
import com.pitstop.shimpyo.core.sim.SimVehicleGateway
import com.pitstop.shimpyo.platform.MainThread
import com.pitstop.shimpyo.platform.PleosServiceProbe
import com.pitstop.shimpyo.platform.navi.PleosNaviGateway
import com.pitstop.shimpyo.platform.speech.SpeechRouter
import com.pitstop.shimpyo.platform.vehicle.PleosVehicleGateway
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Calendar
import kotlin.concurrent.thread

class ShimpyoApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

/**
 * 앱 전체에서 하나만 쓰는 객체 묶음. 기기 환경에 맞춰 Pleos 구현과 시뮬레이터를 고른다.
 *  - Pleos Vehicle 서비스가 있으면 PleosVehicleGateway, 없으면 SimVehicleGateway
 *  - Pleos Navi 서비스가 있으면 PleosNaviGateway, 없으면 SimNaviGateway
 *  - 음성은 Pleos → 안드로이드 기본 순서로 자동 선택
 * 컨트롤러는 화면이 다시 만들어져도 유지되도록 Application에 둔다.
 */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    val pleosVehicle: PleosVehicleGateway? =
        if (PleosServiceProbe.hasVehicle(appContext)) PleosVehicleGateway(appContext) else null
    // 실제 작동판에서 차량 서비스를 못 찾으면 시뮬레이터가 ‘완료’를 꾸며 내지 않게 제어를 모두 ‘지원 안 함’으로 둔다.
    // 화면에는 “차량·내비 연결 안 됨”을 띄운다(ShimpyoScreen.Actions).
    val simVehicle: SimVehicleGateway? =
        if (pleosVehicle == null) SimVehicleGateway(controlsWork = BuildConfig.DEMO_TOOLS) else null
    private val vehicle: VehicleGateway = pleosVehicle ?: simVehicle!!

    private val pleosNavi: PleosNaviGateway? =
        if (PleosServiceProbe.hasNavi(appContext)) PleosNaviGateway(appContext) else null
    val simNavi: SimNaviGateway? = if (pleosNavi == null) SimNaviGateway(controlsWork = BuildConfig.DEMO_TOOLS) else null
    private val navi: NaviGateway = pleosNavi ?: simNavi!!

    private val speech = SpeechRouter(appContext)

    /** 시연용 시간대 고정(예: 새벽 2시). null이면 실제 시각. */
    var demoHour: Int? by mutableStateOf<Int?>(null)

    private val clock = object : Clock {
        override fun nowMs(): Long = SystemClock.elapsedRealtime()
        override fun hourOfDay(): Int = demoHour ?: Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    }

    var uiState: UiState by mutableStateOf(UiState())
        private set
    var simCabin: SimVehicleGateway.Cabin? by mutableStateOf(simVehicle?.cabin)
        private set
    var demoMode: Boolean by mutableStateOf(false)
        private set
    var manualParked: Boolean? by mutableStateOf<Boolean?>(null)
        private set
    /** 시연 패널에서 지정한 동승자 여부. null이면 차량 착좌 신호를 따른다. */
    var manualPassenger: Boolean? by mutableStateOf<Boolean?>(if (simVehicle != null) false else null)
        private set

    /**
     * 출발 전 정보(무입력 출발). 지금은 시연용이라 패널에서 켠 값만 들어간다.
     * 실제 헬스 앱·캘린더 연동은 확장 단계(스마트폰 동반 앱 → 차량)에서 붙인다.
     */
    private val preDrive = SimPreDriveSource()
    /** 시연용 워치 수면 시간. null이면 워치 기록 없음(출발 전에 음성으로 묻는다). */
    var demoWearableHours: Double? by mutableStateOf<Double?>(null)
        private set
    var demoCalendar: Boolean by mutableStateOf(false)
        private set

    private var shelterDirectory: ShelterDirectory? = null

    /** 미니맵에 찍을 졸음쉼터 전체. 데이터를 다 읽으면 채워진다. */
    var shelters: List<Shelter> by mutableStateOf(emptyList())
        private set

    /** 시연 패널의 ‘가상 주행’이 켜져 있는지. 가상 내비와 Pleos 내비 모두에서 쓸 수 있다. */
    var simDriving: Boolean by mutableStateOf(false)
        private set
    /** 앱이 Pleos 내비에 붙어 있는지(시연 패널의 ‘실제 위치’ 버튼 표시용). */
    val usesPleosNavi: Boolean get() = pleosNavi != null
    private var simDriveTarget: Shelter? = null
    private var simPosition: GeoPoint? = null

    /** 같이하기 기본값은 앱을 다시 켜도 남도록 저장한다. */
    private val prefs = appContext.getSharedPreferences("shimpyo", Context.MODE_PRIVATE)

    private fun savedTeamPreference(): TeamPreference =
        runCatching { TeamPreference.valueOf(prefs.getString(KEY_TEAM, null) ?: "") }.getOrDefault(TeamPreference.ASK)

    val controller = ShimpyoController(
        vehicle, navi, speech, clock, // 자동 시작은 실제 차량 신호가 있을 때만. 차량 서비스가 없는 기기에서는 가상 차량이 처음부터 ‘주행’이라 켜자마자 시작해 버린다.
        ShimpyoConfig(autoStartOnDrive = !BuildConfig.DEMO_TOOLS && pleosVehicle != null),
        preDrive = preDrive,
        initialTeamPreference = savedTeamPreference(),
    ) { s ->
        // 운행이 시작되면 포그라운드 서비스를 켜고, 끝나면 끈다(내비가 앞으로 나와도 앱이 얼지 않게).
        val wasActive = uiState.phase != Phase.IDLE
        val active = s.phase != Phase.IDLE
        uiState = s
        if (active != wasActive) {
            if (active) com.pitstop.shimpyo.platform.TripService.start(appContext)
            else com.pitstop.shimpyo.platform.TripService.stop(appContext)
        }
    }

    /** 설정 화면의 같이하기 기본값. 주차 중이 아니면 바뀌지 않는다(컨트롤러가 거절). */
    fun applyTeamPreference(preference: TeamPreference) {
        if (controller.setTeamPreference(preference)) {
            prefs.edit().putString(KEY_TEAM, preference.name).apply()
        }
    }

    /** 1초마다 컨트롤러를 깨운다. */
    private fun scheduleTick() {
        MainThread.postDelayed(1_000L) {
            advanceSimDrive()
            controller.tick()
            scheduleTick()
        }
    }

    init {
        // 기록 탭과 같은 내용을 logcat(태그 ShimpyoLog)으로도 내보낸다: adb logcat -s ShimpyoLog
        controller.logSink = { Log.i("ShimpyoLog", it) }
        simVehicle?.onCabinChanged = { simCabin = it }
        controller.attach()
        loadShelters()
        scheduleTick()
    }

    fun applyDemoMode(enabled: Boolean) {
        if (!BuildConfig.DEMO_TOOLS) return   // 실제 작동판에는 시연 조작이 없다
        demoMode = enabled
        controller.config = controller.config.copy(timeScale = if (enabled) DEMO_TIME_SCALE else 1)
    }

    /** 주차 상태 지정. 시뮬레이터에서는 가상 차량을, Pleos 차량에서는 AAOS 신호를 덮어쓴다(null=자동). */
    fun setParked(value: Boolean?) {
        if (!BuildConfig.DEMO_TOOLS) return   // 실제 작동판에는 시연 조작이 없다
        manualParked = value
        simVehicle?.setParked(value ?: false)
        pleosVehicle?.overrideParked(value)
    }

    /** 동승자 지정. 시뮬레이터에서는 가상 착좌 센서를, Pleos 차량에서는 차량 신호를 덮어쓴다(null=자동). */
    fun setPassenger(value: Boolean?) {
        if (!BuildConfig.DEMO_TOOLS) return   // 실제 작동판에는 시연 조작이 없다
        manualPassenger = value
        simVehicle?.setPassenger(value ?: false)
        pleosVehicle?.overridePassenger(value)
    }

    /**
     * 시연용 워치 수면 기록. 다음 운행 시작부터 적용된다.
     * 수면 시간에 따라 출발 실내가 달라진다: 7시간 30분=평소, 5시간 30분=온도만 살짝, 4시간=외기·시원하게·첫 휴식 1시간.
     * 기상 시각은 오전 6시로 둔다.
     */
    fun applyDemoWearable(hours: Double?) {
        if (!BuildConfig.DEMO_TOOLS) return   // 실제 작동판에는 시연 조작이 없다
        demoWearableHours = hours
        preDrive.sleep = hours?.let { SleepRecord(sleepHours = it, wakeHour = 6.0, sourceLabel = "갤럭시 워치") }
    }

    /** 시연용 캘린더 일정: 3시간 장거리 출장. 다음 운행 시작부터 적용된다. */
    fun applyDemoCalendar(enabled: Boolean) {
        if (!BuildConfig.DEMO_TOOLS) return   // 실제 작동판에는 시연 조작이 없다
        demoCalendar = enabled
        preDrive.plan = if (enabled) PlannedDrive(title = "부산 출장", expectedMinutes = 180, night = false) else null
    }

    /**
     * 가상 주행: 불러온 첫 쉼터에서 남쪽으로 약 11km 떨어진 지점에서 북쪽으로 달리는 상황을 만든다.
     * 그 뒤로는 1초마다 조금씩 앞으로 나아가서 미니맵에서 차가 움직인다([advanceSimDrive]).
     * Pleos 에뮬레이터에서도 쓸 수 있다(에뮬레이터 내비 위치 대신 이 위치를 쓴다).
     */
    fun simulateDrivingTowardFirstShelter(): Boolean {
        if (!BuildConfig.DEMO_TOOLS) return false
        val target = shelterDirectory?.shelters?.firstOrNull() ?: return false
        val start = GeoPoint(target.point.lat - 0.10, target.point.lon)
        val next = GeoPoint(target.point.lat - 0.09, target.point.lon)
        feedLocation(start)
        feedLocation(next)
        simDriveTarget = target
        simPosition = next
        simDriving = true
        return true
    }

    /** 가상 주행을 멈춘다. Pleos 내비면 다시 에뮬레이터(실차) 위치를 쓴다. */
    fun stopSimDrive() {
        simDriving = false
        simDriveTarget = null
        simPosition = null
        pleosNavi?.clearSimulatedLocation()
    }

    /** 경유지 도착 알림(시연용). */
    fun simulateArrival() {
        if (!BuildConfig.DEMO_TOOLS) return   // 실제 작동판에는 시연 조작이 없다
        simNavi?.simulateArrival()
        pleosNavi?.simulateArrival()
    }

    private fun feedLocation(point: GeoPoint) {
        simNavi?.simulateLocation(point)
        pleosNavi?.simulateLocation(point)
    }

    /**
     * 가상 주행 한 걸음. 운행 중이고 주차가 아닐 때만 움직인다.
     * 경유지가 생기면 그쪽으로 가서 닿으면 도착 알림을 보내고, 없으면 첫 쉼터 1.5km 앞에서 멈춰 기다린다.
     */
    private fun advanceSimDrive() {
        if (!simDriving) return
        val here = simPosition ?: return
        val ui = uiState
        if (ui.phase == Phase.IDLE || ui.parked == true) return
        val waypoint = ui.restStop
        val target = waypoint ?: simDriveTarget ?: return
        val stopKm = if (waypoint != null) 0.0 else SIM_STOP_BEFORE_KM
        val left = Geo.distanceKm(here, target.point)
        if (left <= stopKm + 0.01) {
            if (waypoint != null && ui.phase == Phase.EN_ROUTE) simulateArrival()
            return
        }
        val f = minOf(SIM_STEP_KM, left - stopKm) / left
        val next = GeoPoint(here.lat + (target.point.lat - here.lat) * f, here.lon + (target.point.lon - here.lon) * f)
        simPosition = next
        feedLocation(next)
    }

    fun diagnostics(): List<String> {
        val rows = mutableListOf(
            "차량: ${uiState.vehicleBackend.label}",
            "내비: ${uiState.naviBackend.label}",
            "판: ${if (BuildConfig.DEMO_TOOLS) "시연판(시연 도구 포함)" else "실제 작동판"}",
            "음성: ${uiState.speechBackend.label} (음성 인식 ${if (speech.canListen) "가능" else if (BuildConfig.DEMO_TOOLS) "불가 → 시연 패널로 대답" else "불가"})",
            "졸음쉼터 데이터: ${uiState.shelterCount}곳",
            "현재 위치: " + (uiState.location?.let { "%.5f, %.5f".format(it.lat, it.lon) } ?: "아직 못 받음") +
                if (simDriving) " (가상 주행)" else "",
            "",
        )
        rows += pleosVehicle?.diagnostics() ?: listOf("Pleos Vehicle 서비스가 없는 기기라 가상 차량으로 동작해요.")
        return rows
    }

    /** assets/shelters.csv(공공데이터포털 졸음쉼터 CSV)를 읽는다. UTF-8이 아니면 EUC-KR로 다시 읽는다. */
    private fun loadShelters() {
        thread(name = "shelters") {
            val dir = try {
                val bytes = appContext.assets.open(SHELTER_ASSET).use { it.readBytes() }
                ShelterDirectory.parseCsv(decode(bytes))
            } catch (t: Throwable) {
                Log.i(TAG, "shelters.csv 없음 또는 읽기 실패: ${t.message}")
                ShelterDirectory(emptyList())
            }
            MainThread.post {
                shelterDirectory = dir
                shelters = dir.shelters
                controller.setShelters(dir)
            }
        }
    }

    private fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: Exception) {
        String(bytes, Charset.forName("EUC-KR"))
    }

    private companion object {
        const val TAG = "Shimpyo"
        const val SHELTER_ASSET = "shelters.csv"
        const val DEMO_TIME_SCALE = 60
        /** 가상 주행이 1초에 나아가는 거리(km). 11km를 1분 남짓에 달린다. */
        const val SIM_STEP_KM = 0.12
        /** 경유지가 없을 때 첫 쉼터 몇 km 앞에서 멈추는지. 쉼터 제안 거리(0.5km 이상)보다 멀게 둔다. */
        const val SIM_STOP_BEFORE_KM = 1.5
        const val KEY_TEAM = "team_preference"
    }
}
