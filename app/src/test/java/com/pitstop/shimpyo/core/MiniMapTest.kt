package com.pitstop.shimpyo.core

import com.pitstop.shimpyo.core.sim.SimNaviGateway
import com.pitstop.shimpyo.core.sim.SimVehicleGateway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** 쉼터 미니맵: 영역·좌표 계산과, 컨트롤러가 화면에 넘기는 위치·쉼터 정보. */
class MiniMapTest {

    private fun near(a: Double, b: Double, eps: Double = 1e-6) = abs(a - b) < eps

    @Test
    fun projectionPutsNorthUpAndEastRight() {
        val v = MapViewport(GeoPoint(37.0, 127.0), halfWidthKm = 20.0, halfHeightKm = 10.0)
        val (cx, cy) = v.project(GeoPoint(37.0, 127.0))
        assertTrue(near(cx, 0.5) && near(cy, 0.5))
        val (_, ny) = v.project(GeoPoint(37.05, 127.0))
        assertTrue("북쪽은 위", ny < 0.5)
        val (ex, _) = v.project(GeoPoint(37.0, 127.05))
        assertTrue("동쪽은 오른쪽", ex > 0.5)
        // 위도 0.0904도 ≈ 10km → 세로 가장자리
        val (_, edge) = v.project(GeoPoint(37.0 + 10.0 / MiniMap.KM_PER_DEG_LAT, 127.0))
        assertTrue(near(edge, 0.0, 1e-3))
    }

    @Test
    fun nearbyViewFitsCarFocusAndAheadWithMargin() {
        val here = GeoPoint(37.0, 127.0)
        val small = MiniMap.viewport(MapScope.NEARBY, here, focus = null, ahead = emptyList(), aspect = 1.0)
        assertTrue("앞쪽에 아무것도 없으면 내 위치가 가운데", here == small.center)
        assertTrue(near(small.halfHeightKm, MiniMap.NEARBY_MIN_KM))

        val focus = GeoPoint(37.06, 127.0) // 약 6.6km 북쪽
        val ahead = listOf(focus, GeoPoint(37.16, 127.04), GeoPoint(37.18, 127.03))
        val v = MiniMap.viewport(MapScope.NEARBY, here, focus, ahead, aspect = 1.2)
        for (p in listOf(here, focus, ahead[1], ahead[2])) {
            val (x, y) = v.project(p)
            assertTrue("가장자리에서 10% 넘게 떨어져 있다: $p → $x, $y", x in 0.1..0.9 && y in 0.1..0.9)
        }
        assertTrue("화면 비를 지킨다", near(v.halfWidthKm / v.halfHeightKm, 1.2))

        // 80km 넘게 떨어진 쉼터는 담지 않는다.
        val far = MiniMap.viewport(MapScope.NEARBY, here, GeoPoint(38.5, 127.0), emptyList(), aspect = 1.0)
        assertTrue(far.halfHeightKm <= MiniMap.NEARBY_MAX_KM)
        assertTrue(far.contains(here))
    }

    @Test
    fun fallsBackToCountryWhenLocationUnknownOrAbroad() {
        assertEquals(MapScope.COUNTRY, MiniMap.effectiveScope(MapScope.NEARBY, null))
        // 안드로이드 에뮬레이터 기본 위치(미국 마운틴뷰)
        val abroad = GeoPoint(37.422, -122.084)
        assertEquals(MapScope.COUNTRY, MiniMap.effectiveScope(MapScope.NEARBY, abroad))
        assertTrue(MiniMap.caption(MapScope.NEARBY, abroad, 253).contains("국내가 아니라"))
        assertEquals(MapScope.NEARBY, MiniMap.effectiveScope(MapScope.NEARBY, GeoPoint(36.5, 127.3)))
        assertTrue(MiniMap.caption(MapScope.NEARBY, null, 253).contains("253곳"))
    }

    @Test
    fun countryViewHoldsEveryRealShelter() {
        val file = listOf("src/main/assets/shelters.csv", "app/src/main/assets/shelters.csv", "shimpyo/app/src/main/assets/shelters.csv")
            .map { java.io.File(it) }.first { it.exists() }
        val dir = ShelterDirectory.parseCsv(file.readText(Charsets.UTF_8))
        assertTrue(dir.size > 200)
        for (aspect in listOf(0.8, 1.0, 1.2, 1.8)) {
            val v = MiniMap.viewport(MapScope.COUNTRY, null, null, emptyList(), aspect)
            val outside = dir.shelters.filterNot { v.contains(it.point) }
            assertTrue("화면 비 $aspect 에서 벗어난 쉼터: ${outside.map { it.name }}", outside.isEmpty())
        }
    }

    @Test
    fun scaleBarIsANiceRoundNumber() {
        assertEquals(5, MiniMap.scaleBarKm(MapViewport(GeoPoint(37.0, 127.0), 12.0, 8.0)))
        assertEquals(100, MiniMap.scaleBarKm(MapViewport(GeoPoint(36.0, 127.0), 300.0, 300.0)))
        assertEquals(1, MiniMap.scaleBarKm(MapViewport(GeoPoint(36.0, 127.0), 0.5, 0.5)))
    }

    // ── 컨트롤러가 넘기는 지도 정보 ───────────────────────────────────────────

    private class Speech(val hold: (String) -> Boolean) : SpeechGateway {
        override val backend = Backend.SIM
        override val canListen = true
        val spoken = mutableListOf<String>()
        var pending: ((String, Long?) -> Unit)? = null
        override fun start() = Unit
        override fun stop() = Unit
        override fun speak(text: String, onDone: () -> Unit) { spoken += text; onDone() }
        override fun listen(timeoutMs: Long, onResult: (String, Long?) -> Unit) {
            val q = spoken.last()
            when {
                hold(q) -> pending = onResult
                q.contains("몇 시간 주무셨") -> onResult("네 시간", 1500L)
                q.contains("일어나셨") -> onResult("새벽 네 시", 1500L)
                q.contains("졸린 정도") -> onResult("8", 2000L)
                else -> onResult("응", 2600L)
            }
        }
        override fun cancel() = Unit
    }

    @Test
    fun controllerPublishesLocationAheadOfferAndWaypoint() {
        var now = 0L
        val clock = object : Clock {
            override fun nowMs() = now
            override fun hourOfDay() = 2
        }
        val navi = SimNaviGateway()
        val speech = Speech { it.contains("쉬었다 갈까요") }
        var ui = UiState()
        val c = ShimpyoController(SimVehicleGateway(), navi, speech, clock, ShimpyoConfig(timeScale = 60), Random(3)) { ui = it }
        c.attach()
        c.setShelters(ShelterDirectory.parseCsv("졸음쉼터명,위도,경도\n앞쪽,37.08,127.00\n더앞쪽,37.30,127.00\n뒤쪽,36.95,127.00"))

        assertNull(ui.location)
        navi.simulateLocation(GeoPoint(36.99, 127.0))
        navi.simulateLocation(GeoPoint(37.00, 127.0))
        assertEquals(GeoPoint(37.00, 127.0), ui.location)
        assertTrue("북쪽으로 진행", ui.headingDeg != null && ui.headingDeg!! < 1.0)
        assertEquals(listOf("앞쪽", "더앞쪽"), ui.aheadShelters.map { it.shelter.name })
        assertNull(ui.offeredShelter)

        // 위치가 한 번에 크게 튀면 진행 방향을 모르는 것으로 되돌리고, 다음 이동부터 다시 잰다.
        navi.simulateLocation(GeoPoint(37.422, -122.084))
        assertTrue("순간 이동 뒤에는 방향을 모른다", ui.headingDeg == null)
        navi.simulateLocation(GeoPoint(36.99, 127.0))
        assertTrue(ui.headingDeg == null)
        navi.simulateLocation(GeoPoint(37.00, 127.0))
        assertTrue("다시 북쪽", ui.headingDeg != null && ui.headingDeg!! < 1.0)

        c.startTrip()
        var guard = 0
        while (speech.pending == null && guard++ < 400) { now += 1000; c.tick() }
        assertEquals(Phase.OFFERING, ui.phase)
        assertEquals("앞쪽", ui.offeredShelter?.shelter?.name)
        assertTrue("경유지는 아직 없다", ui.restStop == null)

        speech.pending!!.invoke("응 쉬었다 가자", 2000L)
        now += 1000; c.tick()
        assertEquals(Phase.EN_ROUTE, ui.phase)
        assertTrue("제안이 끝나면 제안 표시는 지운다", ui.offeredShelter == null)
        assertEquals("앞쪽", ui.restStop?.name)

        c.endTrip()
        assertTrue("운행을 마치면 경유지 표시도 지운다", ui.restStop == null)
    }
}
