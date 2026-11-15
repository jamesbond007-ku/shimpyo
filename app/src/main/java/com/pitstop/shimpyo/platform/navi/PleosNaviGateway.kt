package com.pitstop.shimpyo.platform.navi

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.ChargerOperator
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import com.pitstop.shimpyo.core.Backend
import com.pitstop.shimpyo.core.GeoPoint
import com.pitstop.shimpyo.core.NaviGateway
import com.pitstop.shimpyo.core.Shelter
import com.pitstop.shimpyo.platform.MainThread

/**
 * Pleos NaviHelper SDK(ai.pleos.playground:NaviHelper:3.0.5) 기반 내비 게이트웨이.
 *
 *  - 생성 → initialize(onConnected, onFailed) → onConnected 뒤에 addListener와 API 호출
 *  - 요청 API는 값을 돌려주지 않고 결과는 NaviHelperEventListener 콜백으로 온다.
 *  - 경유지는 최대 2개(WaypointIndex.FIRST/SECOND). 쉼표는 FIRST만 쓴다.
 *  - 현재 위치는 getCurrentLocationInfo()로 주기적으로 요청한다(진행 방향 계산용).
 *
 * 시그니처는 Pleos 공식 문서와, Pleos Connect Emulator v2.1.0에서 검증된 공개 프로젝트의
 * NaviHelper 3.0.5 사용 코드를 기준으로 했다.
 */
class PleosNaviGateway(context: Context) : NaviGateway {

    override val backend = Backend.PLEOS

    private val appContext = context.applicationContext
    private var helper: NaviHelper? = null
    private var connected = false
    override val available: Boolean get() = connected
    private var listener: NaviGateway.Listener? = null
    private var restStopName: String? = null
    /**
     * 쉼표가 넣은 경유지에 이미 도착했는지. 도착한 경유지는 내비가 지나간 것으로 처리하므로, 그 뒤에 FIRST를 지우면
     * 운전자가 원래 넣어 둔 다음 경유지가 지워질 수 있다. 그래서 도착 뒤에는 지우지 않는다(실차·에뮬레이터 확인 필요).
     */
    private var restStopArrived = false
    private var poll: Runnable? = null
    /** 시연 패널의 ‘가상 주행’이 위치를 대신 넣고 있는지. */
    private var simulating = false

    private val events = object : NaviHelperEventListener {
        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) {
            MainThread.post {
                if (restStopName != null) restStopArrived = true
                listener?.onRestStopArrived(waypointArrivedInfo.name ?: restStopName)
            }
        }

        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            val lat = currentLocationInfo.latitude
            val lon = currentLocationInfo.longitude
            // GPS를 잡기 전에는 0,0이 올 수 있다. 그 점으로 진행 방향을 계산하면 방향·미니맵이 튄다.
            if (lat.isNaN() || lon.isNaN() || (lat == 0.0 && lon == 0.0) || lat !in -90.0..90.0 || lon !in -180.0..180.0) return
            val p = GeoPoint(lat, lon)
            // 시연용 가상 주행 중에는 내비 위치를 쓰지 않는다(두 위치가 번갈아 들어와 방향이 튀지 않게).
            MainThread.post { if (!simulating) listener?.onLocation(p) }
        }

        override fun onError(errorCode: NaviErrorCode) = log("오류 ${errorCode.name}")

        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = log("경로 안내 시작")
        override fun onRoutingCompleted() = Unit

        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onRouteEnded() = Unit

        override fun onReRouteEnded() = Unit
        override fun onRouteCancelled() = log("경로 안내 취소")
        override fun onDestinationChanged() = Unit
        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) = Unit
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) = log("목적지 도착")
        override fun onDestinationInfo(destinationInfo: DestinationInfo) = Unit
        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) = Unit
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = Unit
        override fun onNavigationStatus(status: NavigationStatus) = Unit
        override fun onRouteOptionChanged(routeOption: RouteOption) = Unit
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) = Unit
        override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) = Unit
        override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) = Unit
        override fun onBookMarkChanged() = Unit
        override fun onChargerOperatorInfo(chargerOperatorList: List<ChargerOperator>) = Unit
        override fun onTripInfo(tripInfo: TripInfo) = Unit
        override fun onDrivingInfo(drivingInfo: DrivingInfo) = Unit
        override fun onCurrentRoadSpeedLimit(speedLimit: Int) = Unit
        override fun onCameraAlert(cameraAlertList: List<CameraAlert>) = Unit
        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) = Unit
    }

    override fun start(listener: NaviGateway.Listener) {
        this.listener = listener
        val h = try {
            NaviHelper(appContext)
        } catch (t: Throwable) {
            log("NaviHelper 생성 실패: ${t.message}")
            return
        }
        helper = h
        try {
            h.initialize(
                onConnected = {
                    MainThread.post {
                        if (helper !== h) return@post
                        h.addListener(events)
                        connected = true
                        log("NaviHelper 연결")
                        startLocationPolling()
                    }
                },
                onFailed = { t -> MainThread.post { log("NaviHelper 연결 실패: ${t.message}") } },
            )
        } catch (t: Throwable) {
            helper = null
            log("NaviHelper initialize 예외: ${t.message}")
        }
    }

    override fun stop() {
        MainThread.cancel(poll)
        poll = null
        val h = helper ?: return
        helper = null
        connected = false
        runCatching { h.removeListener(events) }
        runCatching { h.release() }
        listener = null
    }

    override fun addRestStop(shelter: Shelter, done: (ok: Boolean, message: String?) -> Unit) {
        val h = helper
        if (h == null || !connected) return done(false, "내비게이션에 연결되지 않음")
        try {
            h.addWaypoint(
                RequestWaypointInfo(
                    latitude = shelter.point.lat,
                    longitude = shelter.point.lon,
                    poiName = shelter.spokenName,
                    waypointIndex = WaypointIndex.FIRST,
                    poiId = "",
                    address = listOf(shelter.road, shelter.direction).filter { it.isNotBlank() }.joinToString(" "),
                    poiSubId = "0",
                ),
            )
            restStopName = shelter.name
            restStopArrived = false
            done(true, null)
        } catch (e: SecurityException) {
            done(false, "권한 거부: ${e.message}")
        } catch (t: Throwable) {
            done(false, t.message ?: t.javaClass.simpleName)
        }
    }

    override fun removeRestStop() {
        val h = helper ?: return
        if (!connected) return
        if (restStopName == null) return // 쉼표가 넣은 경유지가 없으면 운전자의 경유지를 건드리지 않는다.
        if (restStopArrived) {
            log("도착한 쉼터 경유지는 내비가 지나간 것으로 보고 지우지 않음")
        } else {
            runCatching { h.removeWaypoint(WaypointIndex.FIRST) }
                .onFailure { log("경유지 삭제 실패: ${it.message}") }
        }
        restStopName = null
        restStopArrived = false
    }

    override fun openShelterSearch() {
        val h = helper ?: return
        if (!connected) return
        runCatching { h.requestNavigationScreen(NavigationScreenRequest.SearchPlace(SEARCH_KEYWORD)) }
            .onFailure { log("검색 화면 요청 실패: ${it.message}") }
    }

    /**
     * 시연용 가상 주행. 에뮬레이터 내비가 주는 위치 대신 [point]를 현재 위치로 쓴다.
     * 경유지 추가 같은 내비 요청은 그대로 Pleos 내비로 간다. 메인 스레드에서 부른다.
     */
    fun simulateLocation(point: GeoPoint) {
        simulating = true
        listener?.onLocation(point)
    }

    /** 가상 주행을 끄고 다시 내비 위치를 쓴다. */
    fun clearSimulatedLocation() {
        simulating = false
    }

    /** 시연용: 가상 주행으로 경유지에 닿았을 때 내비의 도착 알림 대신 부른다. */
    fun simulateArrival() {
        listener?.onRestStopArrived(restStopName)
    }

    private fun startLocationPolling() {
        val h = helper ?: return
        runCatching { h.getCurrentLocationInfo() }
        poll = MainThread.postDelayed(LOCATION_POLL_MS) { startLocationPolling() }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        MainThread.post { listener?.onLog(message) }
    }

    private companion object {
        const val TAG = "ShimpyoNavi"
        const val SEARCH_KEYWORD = "졸음쉼터"
        const val LOCATION_POLL_MS = 5_000L
    }
}
