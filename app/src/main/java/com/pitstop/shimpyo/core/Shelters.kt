package com.pitstop.shimpyo.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 같은 이름의 쉼터가 이 거리 안에 있으면 상·하행 짝으로 본다(km). */
internal const val PAIR_KM = 1.0
/** 방향을 모를 때, 한쪽 차로에만 있는 쉼터 대신 이만큼 더 가서 있는 양쪽 쉼터를 고른다(km). */
internal const val PAIR_DETOUR_KM = 10.0
/** 쉼터 짝 사이를 지나는지 볼 거리(km). */
internal const val SIDE_CHECK_KM = 0.25
/** 짝의 두 쉼터가 좌우로 적어도 이만큼 떨어져 있어야 판단한다(km). */
internal const val SIDE_MARGIN_KM = 0.008
/** 좌우 차이가 짝 사이 거리의 이 비율보다 커야 판단한다(진행 방향 오차 약 11도까지 견딘다). */
internal const val SIDE_RATIO = 0.2

/** 졸음쉼터·휴게소 한 곳. */
data class Shelter(
    val name: String,
    val point: GeoPoint,
    val road: String = "",
    val direction: String = "",
    /** 시도명(예: 경상북도). 데이터에 없으면 빈 문자열. */
    val province: String = "",
    /** 시군구명(예: 칠곡군). */
    val district: String = "",
    /** 주차면수. 모르면 null. */
    val parkingSpaces: Int? = null,
) {
    /** 말로 부를 이름. 원본 데이터는 “오창”처럼 지명만 있어서 “오창 졸음쉼터”로 붙여 부른다. */
    val spokenName: String
        get() = if (name.endsWith("쉼터") || name.endsWith("휴게소")) name else "$name 졸음쉼터"
}

data class ShelterCandidate(val shelter: Shelter, val distanceKm: Double)

/**
 * 달리는 노선과 방향. [direction]은 공공데이터의 ‘도로노선방향’ 값 그대로(예: “통영기점 + 하남종점” = 하남 쪽으로 가는 차로).
 * 원본에 오타가 몇 개 있어(양앙·회턱·양기점) 비교할 때 바로잡는다.
 */
data class Travel(val road: String, val direction: String) {
    companion object {
        private val FIXES = listOf("양앙" to "양양", "회턱" to "회덕")
        /** 지명 전체가 잘린 오타(중부내륙선 “양기점” = 양평기점). 부분 문자열로 바꾸면 “양양기점”까지 바뀌므로 지명 단위로 고친다. */
        private val PLACE_FIXES = mapOf("양" to "양평")
        private val PATTERN = Regex("(\\S+)기점\\s*\\+\\s*(\\S+)종점")

        private fun ends(d: String): Pair<String, String>? {
            var t = d.trim()
            FIXES.forEach { (a, b) -> t = t.replace(a, b) }
            val m = PATTERN.matchEntire(t) ?: return null
            fun fix(p: String) = PLACE_FIXES[p] ?: p
            return fix(m.groupValues[1]) to fix(m.groupValues[2])
        }

        /** 두 방향 값이 정확히 반대인지(A→B와 B→A). 모르는 꼴이면 false(반대라고 단정하지 않는다). */
        fun isReverse(d1: String, d2: String): Boolean {
            val a = ends(d1) ?: return false
            val b = ends(d2) ?: return false
            return a.first == b.second && a.second == b.first
        }

        /** 같은 방향인지. */
        fun sameWay(d1: String, d2: String): Boolean {
            val a = ends(d1) ?: return d1 == d2
            val b = ends(d2) ?: return d1 == d2
            return a == b
        }
    }
}

/**
 * 공공데이터포털 ‘한국도로공사 졸음쉼터’ CSV를 읽어 앞쪽의 가장 가까운 쉼터를 찾는다.
 * https://www.data.go.kr/data/15028203/fileData.do
 * (항목: 졸음쉼터명, 도로노선명, 도로노선방향, 위도, 경도 …)
 *
 * 헤더 이름으로 열을 찾기 때문에 열 순서가 바뀌어도 동작한다.
 * 앱에는 전국졸음쉼터표준데이터(2026-02-25 기준, 253곳)를 assets/shelters.csv로 넣었다.
 * 출처: 공공데이터포털(한국도로공사), 이용허락범위 제한 없음. 해마다 갱신되므로 새 파일로 바꿔 넣으면 된다.
 */
class ShelterDirectory(val shelters: List<Shelter>) {

    val size: Int get() = shelters.size

    /** 같은 이름의 짝(반대 차로 쉼터)이 1km 안에 있는지. 없으면 한쪽 차로에만 있는 쉼터다. */
    private fun hasPair(s: Shelter): Boolean =
        shelters.any { it !== s && it.name == s.name && Geo.distanceKm(it.point, s.point) < PAIR_KM }

    /** 지금 달리는 쪽([travel])에서 들어갈 수 없는 반대 차로 쉼터인지. 같은 노선에서 방향 값이 정확히 반대일 때만 그렇다고 본다. */
    private fun oppositeSide(s: Shelter, travel: Travel?): Boolean =
        travel != null && s.road == travel.road && Travel.isReverse(s.direction, travel.direction)

    /**
     * 현재 위치 기준으로 진행 방향 앞쪽의 가장 가까운 쉼터.
     * [headingDeg]가 없으면 방향을 따지지 않고 가장 가까운 곳을 고른다.
     *
     * 졸음쉼터의 절반쯤은 한쪽 차로에만 있다(253곳 중 같은 이름의 짝이 없는 곳 81곳). 반대 차로 쉼터를 경유지로 넣으면
     * 다음 나들목에서 돌아와야 해서 졸린 운전자를 오히려 더 달리게 만든다. 그래서
     *  - 달리는 방향([travel])을 알면 반대 차로 쉼터를 뺀다.
     *  - 모르면 한쪽에만 있는 쉼터 대신, [PAIR_DETOUR_KM] 안쪽으로 더 가서 있는 양쪽 쉼터를 먼저 고른다(어느 쪽이든 들어갈 수 있다).
     */
    fun nearestAhead(
        from: GeoPoint,
        headingDeg: Double?,
        minKm: Double = 0.5,
        maxKm: Double = 40.0,
        maxAngleDeg: Double = 50.0,
        /**
         * 지금 달리는 것으로 보이는 노선(예: 중부선). 주면 그 노선의 쉼터를 먼저 고른다.
         * 분기점 근처에서 옆 고속도로의 쉼터를 골라 크게 돌아가게 만드는 것을 막는다. 그 노선에 앞쪽 쉼터가 없으면 전체에서 고른다.
         */
        preferRoad: String? = null,
        /** 지금 달리는 노선과 방향(쉼터 짝 사이를 지날 때 알아낸 값). 모르면 null. */
        travel: Travel? = null,
    ): ShelterCandidate? {
        val ahead = shelters.asSequence()
            .map { ShelterCandidate(it, Geo.distanceKm(from, it.point)) }
            .filter { it.distanceKm in minKm..maxKm }
            .filter { c ->
                headingDeg == null || Geo.angleDiff(headingDeg, Geo.bearingDeg(from, c.shelter.point)) <= maxAngleDeg
            }
            .filter { !oppositeSide(it.shelter, travel) }
            .toList()
        val road = travel?.road ?: preferRoad
        val sameRoad = road?.takeIf { it.isNotBlank() }?.let { r -> ahead.filter { it.shelter.road == r } }.orEmpty()
        val pool = sameRoad.ifEmpty { ahead }
        var best = pool.minByOrNull { it.distanceKm } ?: return null
        if (travel == null && headingDeg != null && !hasPair(best.shelter)) {
            pool.filter { hasPair(it.shelter) && it.distanceKm <= best.distanceKm + PAIR_DETOUR_KM }
                .minByOrNull { it.distanceKm }?.let { best = it }
        }
        if (headingDeg == null) return best
        val siblings = shelters.filter { it.name == best.shelter.name && Geo.distanceKm(it.point, best.shelter.point) < PAIR_KM }
        if (siblings.size < 2) return best
        // 짝 중에서 우리 쪽 차로 쉼터를 고른다. 방향을 알면 방향 값으로, 모르면 우측통행이라 진행 방향 오른쪽에 있는 곳으로.
        val pick = travel?.let { t -> siblings.firstOrNull { it.road == t.road && Travel.sameWay(it.direction, t.direction) } }
            ?: siblings.maxBy { Geo.lateralKm(from, it.point, headingDeg) }
        return ShelterCandidate(pick, Geo.distanceKm(from, pick.point))
    }

    /**
     * 쉼터 짝(상·하행) 사이를 지나는 중이면 지금 달리는 노선·방향을 알아낸다. 우측통행이라 오른쪽 쉼터가 우리 쪽이다.
     * 가까운(0.25km 안) 짝에서, 한쪽이 다른 쪽보다 뚜렷이 오른쪽에 있을 때만 판단한다(뚜렷하지 않으면 null).
     */
    fun travelAt(here: GeoPoint, headingDeg: Double?): Travel? {
        val h = headingDeg ?: return null
        val near = shelters.filter { Geo.distanceKm(here, it.point) <= SIDE_CHECK_KM }.sortedBy { Geo.distanceKm(here, it.point) }
        for (s in near) {
            val pair = shelters.filter { it.name == s.name && it.road == s.road && Geo.distanceKm(it.point, s.point) < PAIR_KM }
            if (pair.size != 2) continue
            val (a, b) = pair
            val la = Geo.lateralKm(here, a.point, h)
            val lb = Geo.lateralKm(here, b.point, h)
            // 내 차는 길 한가운데가 아니라 한쪽 차로에 있으므로 ‘양쪽에 갈라져 있는지’가 아니라 둘 중 어느 쪽이 더 오른쪽인지를 본다.
            // 짝이 길을 따라 어긋나 있으면 진행 방향 오차만큼 좌우 값이 흔들리므로, 차이가 짝 사이 거리의 [SIDE_RATIO]보다 클 때만 믿는다.
            val need = maxOf(SIDE_MARGIN_KM, Geo.distanceKm(a.point, b.point) * SIDE_RATIO)
            val right = when {
                la - lb > need -> a
                lb - la > need -> b
                else -> continue
            }
            if (right.direction.isBlank() || right.road.isBlank()) continue
            return Travel(right.road, right.direction)
        }
        return null
    }

    /**
     * 지금 위치 근처의 쉼터를 가까운 순서로(방향은 따지지 않음). 같은 이름(상·하행 한 쌍)은 하나만.
     * ‘지금 지나는 지역’을 알 때 쓴다. 별도 행정구역 지도 없이, 가장 가까운 쉼터의 시도·시군구로 지역을 가늠한다.
     */
    fun nearby(from: GeoPoint, maxKm: Double = 40.0, limit: Int = 3): List<ShelterCandidate> =
        shelters.asSequence()
            .map { ShelterCandidate(it, Geo.distanceKm(from, it.point)) }
            .filter { it.distanceKm <= maxKm }
            .sortedBy { it.distanceKm }
            .distinctBy { it.shelter.name }
            .take(limit)
            .toList()

    /** 진행 방향 앞쪽 쉼터를 가까운 순서로. 같은 이름은 하나만. */
    fun aheadList(from: GeoPoint, headingDeg: Double?, limit: Int = 2, maxKm: Double = 80.0, travel: Travel? = null): List<ShelterCandidate> =
        shelters.asSequence()
            .map { ShelterCandidate(it, Geo.distanceKm(from, it.point)) }
            .filter { it.distanceKm in 0.5..maxKm }
            .filter { !oppositeSide(it.shelter, travel) }
            .filter { c -> headingDeg == null || Geo.angleDiff(headingDeg, Geo.bearingDeg(from, c.shelter.point)) <= 50.0 }
            .sortedBy { it.distanceKm }
            .distinctBy { it.shelter.name }
            .take(limit)
            .toList()

    companion object {
        private val KOREA_LAT = 33.0..39.0
        private val KOREA_LON = 124.0..132.0

        fun parseCsv(text: String): ShelterDirectory {
            val rows = Csv.parse(text.removePrefix("﻿"))
            if (rows.isEmpty()) return ShelterDirectory(emptyList())
            val header = rows.first().map { it.trim() }
            fun col(vararg keys: String): Int = header.indexOfFirst { h -> keys.any { h.contains(it) } }
            val nameCol = col("쉼터명", "휴게소명", "시설명", "명칭")
            val latCol = col("위도")
            val lonCol = col("경도")
            val roadCol = col("도로노선명", "노선명")
            val dirCol = col("도로노선방향", "방향")
            val provinceCol = col("시도명")
            val districtCol = col("시군구명")
            val parkingCol = col("주차면수")
            if (nameCol < 0 || latCol < 0 || lonCol < 0) return ShelterDirectory(emptyList())

            val shelters = rows.drop(1).mapNotNull { r ->
                val lat = r.getOrNull(latCol)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
                val lon = r.getOrNull(lonCol)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
                if (lat !in KOREA_LAT || lon !in KOREA_LON) return@mapNotNull null
                val name = r.getOrNull(nameCol)?.trim().orEmpty().ifEmpty { return@mapNotNull null }
                Shelter(
                    name = name,
                    point = GeoPoint(lat, lon),
                    road = r.getOrNull(roadCol)?.trim().orEmpty(),
                    direction = r.getOrNull(dirCol)?.trim().orEmpty(),
                    province = r.getOrNull(provinceCol)?.trim().orEmpty(),
                    district = r.getOrNull(districtCol)?.trim().orEmpty(),
                    parkingSpaces = r.getOrNull(parkingCol)?.trim()?.toIntOrNull(),
                )
            }
            return ShelterDirectory(shelters)
        }
    }
}

object Geo {
    private const val EARTH_KM = 6371.0

    fun distanceKm(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_KM * asin(sqrt(h))
    }

    /** a에서 b를 바라본 방위각(0=북, 90=동). */
    fun bearingDeg(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** 진행 방향 [headingDeg] 기준으로 b가 a의 오른쪽에 얼마나 떨어져 있는지(km, 오른쪽이 +). */
    fun lateralKm(a: GeoPoint, b: GeoPoint, headingDeg: Double): Double {
        val east = Math.toRadians(b.lon - a.lon) * cos(Math.toRadians((a.lat + b.lat) / 2)) * EARTH_KM
        val north = Math.toRadians(b.lat - a.lat) * EARTH_KM
        val h = Math.toRadians(headingDeg)
        return east * cos(h) - north * sin(h)
    }

    fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180) 360 - d else d
    }
}

/** 따옴표를 지원하는 최소 CSV 파서. */
object Csv {
    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        cell.append('"'); i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    cell.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> { row += cell.toString(); cell.clear() }
                    '\r' -> Unit
                    '\n' -> {
                        row += cell.toString(); cell.clear()
                        if (row.any { it.isNotBlank() }) rows += row.toList()
                        row.clear()
                    }
                    else -> cell.append(c)
                }
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row += cell.toString()
            if (row.any { it.isNotBlank() }) rows += row.toList()
        }
        return rows
    }
}
