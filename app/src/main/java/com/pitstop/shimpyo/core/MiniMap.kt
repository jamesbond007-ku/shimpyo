package com.pitstop.shimpyo.core

import kotlin.math.cos

/**
 * 쉼터 미니맵 계산. 화면(Compose)은 여기서 나온 0~1 좌표를 그리기만 한다.
 *
 * 바탕 지도(도로·지명) 없이 졸음쉼터 공공데이터 253곳의 좌표만 점으로 찍는 개요도라서
 * 지도 API 키나 인터넷이 필요 없다. 길안내는 하지 않는다(Pleos ‘경로 하나 · 내비 하나’ 원칙,
 * 길안내는 Pleos 내비 앱의 몫).
 */
enum class MapScope { NEARBY, COUNTRY }

/** 화면 한 장에 담는 영역. [halfWidthKm]·[halfHeightKm]는 가운데에서 가장자리까지 거리. */
data class MapViewport(val center: GeoPoint, val halfWidthKm: Double, val halfHeightKm: Double) {

    /** 위경도를 화면 비율 좌표로. x는 왼쪽 0 → 오른쪽 1, y는 위 0 → 아래 1. 북쪽이 위. */
    fun project(p: GeoPoint): Pair<Double, Double> {
        val eastKm = (p.lon - center.lon) * MiniMap.KM_PER_DEG_LON_EQUATOR * cos(Math.toRadians(center.lat))
        val northKm = (p.lat - center.lat) * MiniMap.KM_PER_DEG_LAT
        return (0.5 + eastKm / (2 * halfWidthKm)) to (0.5 - northKm / (2 * halfHeightKm))
    }

    fun contains(p: GeoPoint, marginRatio: Double = 0.0): Boolean {
        val (x, y) = project(p)
        return x in -marginRatio..(1 + marginRatio) && y in -marginRatio..(1 + marginRatio)
    }
}

object MiniMap {
    const val KM_PER_DEG_LAT = 110.57
    const val KM_PER_DEG_LON_EQUATOR = 111.32

    /** 졸음쉼터 데이터가 들어 있는 범위(제주 포함). 이 밖이면 ‘주변’ 보기를 할 수 없다. */
    private val LAT = 33.0..38.7
    private val LON = 124.5..130.0
    private val KOREA_CENTER = GeoPoint(35.85, 127.75)

    /** 주변 보기의 최소·최대 반경(km). */
    const val NEARBY_MIN_KM = 8.0
    const val NEARBY_MAX_KM = 80.0
    /** 점 둘레 여백 배율. 이름표가 들어갈 자리. */
    private const val MARGIN = 1.5

    fun inKorea(p: GeoPoint?): Boolean = p != null && p.lat in LAT && p.lon in LON

    /** 위치를 모르거나 국내가 아니면 전국 보기로 바꾼다. */
    fun effectiveScope(requested: MapScope, location: GeoPoint?): MapScope =
        if (requested == MapScope.NEARBY && inKorea(location)) MapScope.NEARBY else MapScope.COUNTRY

    /**
     * 보여 줄 영역. 주변 보기는 내 위치, 고른 쉼터([focus]), 앞쪽 쉼터(화면에 그리는 최대 세 곳)가 여백을 두고 모두 들어오게
     * 맞춘다(이름표가 가장자리에 붙지 않게). 앞쪽에 아무것도 없으면 내 위치를 가운데 두고 반경 8km.
     * [aspect]는 화면 가로/세로 비.
     */
    fun viewport(
        scope: MapScope,
        location: GeoPoint?,
        focus: GeoPoint?,
        ahead: List<GeoPoint>,
        aspect: Double,
    ): MapViewport {
        val a = aspect.coerceIn(0.3, 3.0)
        val here = location
        if (effectiveScope(scope, here) == MapScope.COUNTRY || here == null) {
            val halfH = (LAT.endInclusive - LAT.start) * KM_PER_DEG_LAT / 2
            val halfW = (LON.endInclusive - LON.start) * KM_PER_DEG_LON_EQUATOR * cos(Math.toRadians(KOREA_CENTER.lat)) / 2
            // 가로·세로 모두 담기도록 큰 쪽에 맞춘다.
            val h = maxOf(halfH, halfW / a) * 1.04
            return MapViewport(KOREA_CENTER, h * a, h)
        }
        // 너무 먼 쉼터까지 담느라 화면이 지나치게 커지지 않게 최대 반경 안쪽 것만 쓴다.
        val points = (listOf(here) + listOfNotNull(focus) + ahead.take(3))
            .filter { Geo.distanceKm(here, it) <= NEARBY_MAX_KM }
        val minLat = points.minOf { it.lat }
        val maxLat = points.maxOf { it.lat }
        val minLon = points.minOf { it.lon }
        val maxLon = points.maxOf { it.lon }
        val center = GeoPoint((minLat + maxLat) / 2, (minLon + maxLon) / 2)
        val needH = (maxLat - minLat) / 2 * KM_PER_DEG_LAT * MARGIN
        val needW = (maxLon - minLon) / 2 * KM_PER_DEG_LON_EQUATOR * cos(Math.toRadians(center.lat)) * MARGIN
        val h = maxOf(needH, needW / a, NEARBY_MIN_KM / minOf(1.0, a)).coerceAtMost(NEARBY_MAX_KM)
        return MapViewport(center, h * a, h)
    }

    /** 축척 막대 길이(km). 화면 가로의 1/4 안쪽에서 가장 큰 1·2·5 단위. */
    fun scaleBarKm(view: MapViewport): Int {
        val target = view.halfWidthKm * 2 / 4
        return SCALE_STEPS.lastOrNull { it <= target } ?: SCALE_STEPS.first()
    }

    /** 지도 위 작은 안내 문구. */
    fun caption(scope: MapScope, location: GeoPoint?, shelterCount: Int): String = when {
        location == null -> "현재 위치를 기다리는 중 · 전국 졸음쉼터 ${shelterCount}곳"
        !inKorea(location) -> "현재 위치가 국내가 아니라 전국으로 보여요"
        effectiveScope(scope, location) == MapScope.NEARBY -> "내 주변 졸음쉼터"
        else -> "전국 졸음쉼터 ${shelterCount}곳"
    }

    private val SCALE_STEPS = listOf(1, 2, 5, 10, 20, 50, 100, 200)
}
