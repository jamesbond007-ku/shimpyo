package com.pitstop.shimpyo.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.pitstop.shimpyo.core.GeoPoint
import com.pitstop.shimpyo.core.MapViewport
import com.pitstop.shimpyo.core.MiniMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan

/**
 * 쉼터 미니맵 바탕에 까는 오픈스트리트맵 타일. 키가 필요 없다.
 *
 * WebView 지도는 이 화면 구조(시트 흐림·전환 레이어)에서 그려지지 않아서, 타일 그림을 직접 내려받아
 * 미니맵 Canvas 맨 아래에 그린다. 쉼터 점·내 위치·권하는 쉼터·이름표는 그 위에 그대로 그린다.
 * 인터넷이 없으면 타일 없이 지금까지의 미니맵(개요도)으로 보인다.
 *
 * 오픈스트리트맵 타일 이용 정책: 앱을 알 수 있는 User-Agent를 보내고, 지도에 출처를 표시하고, 받은 타일은 캐시한다.
 * 시연·시제품 수준의 적은 사용량을 전제로 한다. 양산 때는 상용 타일 서비스(또는 구글 지도 키)로 바꾼다.
 */
internal object MapTiles {
    /**
     * 지도 출처. 구글 지도 키(local.properties의 MAPS_API_KEY, Google Map Tiles API 사용 설정)가 있으면 구글 지도,
     * 키가 없거나 세션 만들기가 거절되면 오픈스트리트맵.
     */
    private enum class Provider(val attribution: String) { GOOGLE("Google"), OSM("© OpenStreetMap") }

    private val googleKey = com.pitstop.shimpyo.BuildConfig.MAPS_API_KEY
    @Volatile private var provider = if (googleKey.isNotBlank()) Provider.GOOGLE else Provider.OSM
    @Volatile private var googleSession: String? = null
    @Volatile private var sessionRequested = false

    /** 지금 쓰는 지도의 출처 표시. */
    val attribution: String get() = provider.attribution
    private const val URL_PATTERN = "https://tile.openstreetmap.org/%d/%d/%d.png"
    private const val GOOGLE_TILE = "https://tile.googleapis.com/v1/2dtiles/%d/%d/%d?session=%s&key=%s"
    // 버전을 손으로 적어 두면 판이 바뀔 때 어긋난다(예전엔 0.2.0인데 0.1로 보냈다). 빌드 버전을 그대로 쓴다.
    private val USER_AGENT = "Shimpyo/${com.pitstop.shimpyo.BuildConfig.VERSION_NAME} (Pleos Connect in-car app prototype)"
    private const val MIN_ZOOM = 5
    private const val MAX_ZOOM = 16
    private const val MAX_CACHE = 160
    /** 구글 타일은 2배 크기(512px, 장당 약 1MB)라 적게 둔다. 화면 하나에 많아야 48장. */
    private const val MAX_CACHE_GOOGLE = 56
    /** 네트워크 오류로 세션을 못 만들었을 때 다시 시도하기까지. 키 거절(4xx)이면 오픈스트리트맵으로 바꾼다. */
    private const val SESSION_RETRY_MS = 60_000L
    private const val RETRY_MS = 30_000L

    /** 받은 타일. 화면이 이 상태를 읽으므로 타일이 도착하면 다시 그려진다. */
    private val loaded = mutableStateMapOf<String, ImageBitmap>()
    private val order = ArrayDeque<String>()
    private val inFlight = HashSet<String>()
    private val failedAt = HashMap<String, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(4)

    /** 타일을 하나라도 받았는지(출처 표시 여부). */
    val anyLoaded: Boolean get() = loaded.keys.any { it != "__refresh__" }

    /** 라이트: 채도를 낮춰 Pleos 화면의 무채색 톤에 맞추고, 쉼터·내 위치 표식이 먼저 보이게 한다. */
    private val lightFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.25f) })

    /** 다크: 밝기를 뒤집고 채도를 낮춘 지도. */
    private val darkFilter = ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                -0.85f, 0f, 0f, 0f, 235f,
                0f, -0.85f, 0f, 0f, 235f,
                0f, 0f, -0.80f, 0f, 240f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )

    /** [view]를 덮는 타일을 그린다. 없는 타일은 내려받기를 시작하고 이번에는 건너뛴다. */
    fun DrawScope.drawTiles(view: MapViewport, dark: Boolean, alpha: Float) {
        // 타일 상태를 읽어 두어야 타일·세션이 도착했을 때 다시 그려진다.
        @Suppress("UNUSED_VARIABLE") val stamp = loaded.size
        if (size.width <= 0f || size.height <= 0f) return
        val latRad = Math.toRadians(view.center.lat)
        val metersPerPixel = view.halfWidthKm * 2000.0 / size.width
        // 화면 1픽셀이 타일 1픽셀쯤 되도록(타일이 너무 흐리거나 너무 많지 않게) 확대 단계를 고른다.
        // 구글 타일은 2배 크기(scaleFactor2x)로 받고 한 단계 낮은 확대로 그려, 차 화면에서 글자가 두 배로 크고 선명하게 보이게 한다.
        val bias = if (provider == Provider.GOOGLE) 1 else 0
        val z = (log2(156_543.03 * cos(latRad) / metersPerPixel).roundToInt() - bias).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val n = 1 shl z
        val halfLon = view.halfWidthKm / (MiniMap.KM_PER_DEG_LON_EQUATOR * cos(latRad))
        val halfLat = view.halfHeightKm / MiniMap.KM_PER_DEG_LAT
        val x0 = tileX(view.center.lon - halfLon, n)
        val x1 = tileX(view.center.lon + halfLon, n)
        val y0 = tileY(view.center.lat + halfLat, n)
        val y1 = tileY(view.center.lat - halfLat, n)
        if ((x1 - x0 + 1) * (y1 - y0 + 1) > 48) return // 지나치게 넓으면 그리지 않는다(전국 보기 등은 개요도로).
        val filter = if (dark) darkFilter else lightFilter
        if (provider == Provider.GOOGLE && googleSession == null) {
            ensureGoogleSession()
            return // 세션이 생기면(또는 거절되어 오픈스트리트맵으로 바뀌면) 다음 그리기부터 타일이 나온다.
        }
        val p = provider
        for (x in x0..x1) for (y in y0..y1) {
            val key = "${p.name}/$z/$x/$y"
            val image = loaded[key]
            if (image == null) {
                request(p, z, x, y, key)
                continue
            }
            val (lx, ty) = view.project(GeoPoint(tileLat(y, n), tileLon(x, n)))
            val (rx, by) = view.project(GeoPoint(tileLat(y + 1, n), tileLon(x + 1, n)))
            val left = (lx * size.width).toFloat()
            val top = (ty * size.height).toFloat()
            val w = ((rx - lx) * size.width).toFloat()
            val h = ((by - ty) * size.height).toFloat()
            drawImage(
                image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset(floor(left).toInt(), floor(top).toInt()),
                // 이웃 타일 사이에 실금이 보이지 않게 1픽셀 겹쳐 그린다.
                dstSize = IntSize(w.roundToInt() + 1, h.roundToInt() + 1),
                alpha = alpha,
                colorFilter = filter,
            )
        }
    }

    /**
     * 구글 Map Tiles API 세션을 한 번 만든다. 거절되면(키에 Map Tiles API가 켜져 있지 않음 등) 오픈스트리트맵으로 바꾼다.
     * https://developers.google.com/maps/documentation/tile/session_tokens
     */
    private fun ensureGoogleSession() {
        if (sessionRequested) return
        sessionRequested = true
        scope.launch {
            var rejected = false
            val session = runCatching {
                val conn = URL("https://tile.googleapis.com/v1/createSession?key=$googleKey").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 5_000
                conn.readTimeout = 8_000
                conn.setRequestProperty("Content-Type", "application/json")
                // 쉼터 이름표가 먼저 보이도록 동네 이름·상점·대중교통 표시는 숨기고 도로·시군 이름만 남긴다.
                val body = """{"mapType":"roadmap","language":"ko-KR","region":"KR","scale":"scaleFactor2x","highDpi":true,""" +
                    """"styles":[{"featureType":"poi","stylers":[{"visibility":"off"}]},""" +
                    """{"featureType":"transit","stylers":[{"visibility":"off"}]},""" +
                    """{"featureType":"administrative.neighborhood","elementType":"labels","stylers":[{"visibility":"off"}]},""" +
                    """{"featureType":"administrative.land_parcel","elementType":"labels","stylers":[{"visibility":"off"}]}]}"""
                conn.outputStream.use { it.write(body.toByteArray()) }
                try {
                    if (conn.responseCode != 200) {
                        rejected = conn.responseCode in 400..499
                        android.util.Log.w("ShimpyoMap", "구글 지도 세션 실패(${conn.responseCode})")
                        null
                    } else {
                        val body = conn.inputStream.bufferedReader().readText()
                        Regex("\"session\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                    }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (session != null) {
                    googleSession = session
                    android.util.Log.i("ShimpyoMap", "구글 지도 사용")
                } else if (rejected) {
                    android.util.Log.w("ShimpyoMap", "구글 지도 키 거절 → 오픈스트리트맵")
                    provider = Provider.OSM
                } else {
                    // 터널·지하 주차장처럼 잠깐 인터넷이 없을 때는 구글 지도를 포기하지 않고 잠시 뒤 다시 시도한다.
                    scope.launch { kotlinx.coroutines.delay(SESSION_RETRY_MS); withContext(Dispatchers.Main) { sessionRequested = false } }
                }
                // 지도를 다시 그리게 하려고 상태를 한 번 건드린다.
                loaded.remove("__refresh__")
                loaded["__refresh__"] = EMPTY
            }
        }
    }

    private val EMPTY by lazy { android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap() }

    private fun request(p: Provider, z: Int, x: Int, y: Int, key: String) {
        synchronized(inFlight) {
            if (key in inFlight) return
            val failed = failedAt[key]
            if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) return
            inFlight += key
        }
        scope.launch {
            val bitmap = runCatching {
                gate.withPermit {
                    val url = if (p == Provider.GOOGLE) GOOGLE_TILE.format(z, x, y, googleSession, googleKey) else URL_PATTERN.format(z, x, y)
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.setRequestProperty("User-Agent", USER_AGENT)
                    conn.connectTimeout = 5_000
                    conn.readTimeout = 8_000
                    conn.useCaches = true
                    try {
                        if (conn.responseCode != 200) null
                        else conn.inputStream.use { BitmapFactory.decodeStream(it) }
                    } finally {
                        conn.disconnect()
                    }
                }
            }.getOrNull()
            withContext(Dispatchers.Main) {
                synchronized(inFlight) { inFlight -= key }
                if (bitmap == null) {
                    synchronized(inFlight) { failedAt[key] = System.currentTimeMillis() }
                } else {
                    loaded[key] = bitmap.asImageBitmap()
                    order.addLast(key)
                    val limit = if (p == Provider.GOOGLE) MAX_CACHE_GOOGLE else MAX_CACHE
                    while (order.size > limit) loaded.remove(order.removeFirst())
                }
            }
        }
    }

    private fun tileX(lon: Double, n: Int) = floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    private fun tileY(lat: Double, n: Int): Int {
        val r = Math.toRadians(lat)
        return floor((1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
    }
    private fun tileLon(x: Int, n: Int) = x.toDouble() / n * 360.0 - 180.0
    private fun tileLat(y: Int, n: Int) = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * y / n))))
}
