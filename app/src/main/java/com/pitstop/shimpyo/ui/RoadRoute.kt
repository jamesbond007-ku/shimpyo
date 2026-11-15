package com.pitstop.shimpyo.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pitstop.shimpyo.core.Geo
import com.pitstop.shimpyo.core.GeoPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 내 위치 → 권하는 쉼터까지 실제 도로 경로(지도에 그리기용). 길안내는 Pleos 순정 내비가 맡고, 이건 미리 보기다.
 *
 * 구글 Routes API는 한국 안에서 자동차 경로를 주지 않는다(국내 지도 데이터 반출 제한, 빈 응답). 그래서 오픈스트리트맵
 * 데이터로 길을 찾는 OSRM 공개 서버를 쓴다(키 필요 없음, 시연·시제품 수준의 적은 사용량 전제). 실패하면 직선 점선으로 그린다.
 */
internal object RoadRoute {
    data class Route(val to: GeoPoint, val from: GeoPoint, val points: List<GeoPoint>, val meters: Double, val seconds: Double)

    // 버전을 손으로 적어 두면 판이 바뀔 때 어긋난다(예전엔 0.2.0인데 0.1로 보냈다). 빌드 버전을 그대로 쓴다.
    private val USER_AGENT = "Shimpyo/${com.pitstop.shimpyo.BuildConfig.VERSION_NAME} (Pleos Connect in-car app prototype)"
    /** 출발점이 이만큼 움직이면 경로를 다시 받는다. */
    private const val REFRESH_KM = 1.0
    private const val RETRY_MS = 30_000L

    /** 화면이 읽는 상태. 경로가 도착하면 지도가 다시 그려진다. */
    var current by mutableStateOf<Route?>(null)
        private set

    private var inFlight = false
    private var failedAt = 0L
    private var failedFor: GeoPoint? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** [from]에서 [to]까지의 경로. 없거나 오래됐으면 받아 오기를 시작하고, 받은 경로가 있으면 돌려준다. */
    fun routeFor(from: GeoPoint, to: GeoPoint): Route? {
        val r = current
        val fresh = r != null && r.to == to && Geo.distanceKm(r.from, from) < REFRESH_KM
        if (!fresh) request(from, to)
        return r?.takeIf { it.to == to }
    }

    private fun request(from: GeoPoint, to: GeoPoint) {
        if (inFlight) return
        if (failedFor == to && System.currentTimeMillis() - failedAt < RETRY_MS) return
        inFlight = true
        scope.launch {
            val route = runCatching {
                val url = "https://router.project-osrm.org/route/v1/driving/" +
                    "${from.lon},${from.lat};${to.lon},${to.lat}?overview=full&geometries=polyline"
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.connectTimeout = 5_000
                conn.readTimeout = 8_000
                try {
                    if (conn.responseCode != 200) return@runCatching null
                    val body = conn.inputStream.bufferedReader().readText()
                    // routes[0]의 거리·시간을 쓴다(waypoints의 distance는 도로에 붙인 거리라 다르다).
                    val first = org.json.JSONObject(body).optJSONArray("routes")?.optJSONObject(0) ?: return@runCatching null
                    Route(to, from, decodePolyline(first.getString("geometry")), first.optDouble("distance"), first.optDouble("duration"))
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
            withContext(Dispatchers.Main) {
                inFlight = false
                if (route != null && route.points.size >= 2) {
                    current = route
                } else {
                    failedAt = System.currentTimeMillis()
                    failedFor = to
                }
            }
        }
    }

}

/** 구글 인코딩 폴리라인(정밀도 1e5) → 좌표 목록. OSRM도 같은 형식으로 준다. */
internal fun decodePolyline(encoded: String): List<GeoPoint> {
        val out = ArrayList<GeoPoint>()
        var i = 0
        var lat = 0
        var lng = 0
        fun next(): Int {
            var result = 0
            var shift = 0
            var b: Int
            do {
                b = encoded[i++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && i < encoded.length)
            return if (result and 1 != 0) (result shr 1).inv() else result shr 1
        }
        while (i < encoded.length) {
            lat += next()
            if (i >= encoded.length) break
            lng += next()
            out += GeoPoint(lat / 1e5, lng / 1e5)
        }
        return out
}
