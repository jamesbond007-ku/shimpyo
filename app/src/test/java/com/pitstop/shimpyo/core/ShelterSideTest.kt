package com.pitstop.shimpyo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 반대 차로 쉼터를 경유지로 넣지 않는지. 실제 데이터(중부선 오창~진천)로 확인한다.
 * 오창 짝 사이를 북쪽(하남 방향)으로 지나면, 바로 앞의 농다리(남쪽 방향 차로에만 있음) 대신 진천(북쪽 방향)을 골라야 한다.
 */
class ShelterSideTest {

    private val dir: ShelterDirectory by lazy {
        val file = listOf("src/main/assets/shelters.csv", "app/src/main/assets/shelters.csv", "shimpyo/app/src/main/assets/shelters.csv")
            .map { java.io.File(it) }.first { it.exists() }
        ShelterDirectory.parseCsv(file.readText(Charsets.UTF_8))
    }

    private val ochangMid = GeoPoint((36.73464638 + 36.73519974) / 2, (127.4566151 + 127.4563134) / 2)

    @Test
    fun passingAPairTellsWhichWayWeDrive() {
        assertEquals(Travel("중부선", "통영기점 + 하남종점"), dir.travelAt(ochangMid, 0.0))
        assertEquals(Travel("중부선", "하남기점 + 통영종점"), dir.travelAt(ochangMid, 180.0))
        assertNull("짝에서 멀면 판단하지 않는다", dir.travelAt(GeoPoint(36.70, 127.45), 0.0))
    }

    @Test
    fun skipsSingleShelterOnTheOppositeCarriageway() {
        val travel = dir.travelAt(ochangMid, 0.0)!!
        val pick = dir.nearestAhead(ochangMid, 0.0, travel = travel)!!
        assertEquals("진천", pick.shelter.name)
        assertEquals("통영기점 + 하남종점", pick.shelter.direction)
        // 방향을 모르던 예전 방식은 반대 차로의 농다리를 골랐다.
        assertEquals("농다리", dir.nearestAhead(ochangMid, 0.0)!!.shelter.name)
        assertFalse(dir.aheadList(ochangMid, 0.0, limit = 3, travel = travel).any { it.shelter.name == "농다리" })
    }

    @Test
    fun picksOurSideOfAPairByDirection() {
        val south = Travel("중부선", "하남기점 + 통영종점")
        // 모가 짝의 북쪽 7km 지점에서 남쪽으로 달리는 중
        val here = GeoPoint(37.26, 127.44)
        val pick = dir.nearestAhead(here, 180.0, travel = south)!!
        assertEquals("모가", pick.shelter.name)
        assertEquals("하남기점 + 통영종점", pick.shelter.direction)
    }

    @Test
    fun directionTyposAreFixedWithoutBreakingOtherNames() {
        assertTrue(Travel.isReverse("서울기점 + 양앙종점", "양양기점 + 서울종점"))
        assertTrue(Travel.isReverse("양기점 + 창원종점", "창원기점 + 양평종점"))
        assertTrue(Travel.isReverse("회턱기점 + 논산종점", "논산기점 + 회덕종점"))
        assertTrue(Travel.sameWay("양양기점 + 서울종점", "양양기점 + 서울종점"))
        assertFalse("모르는 꼴은 반대라고 단정하지 않는다", Travel.isReverse("퇴계원기점 + 구리종점", "판교기점 + 일산종점"))
    }

    @Test
    fun withoutDirectionPrefersAPairShelterIfNotMuchFarther() {
        val d = ShelterDirectory(
            listOf(
                Shelter("한쪽", GeoPoint(37.03, 127.0), road = "가선", direction = "A기점 + B종점"),
                Shelter("양쪽", GeoPoint(37.08, 127.0003), road = "가선", direction = "A기점 + B종점"),
                Shelter("양쪽", GeoPoint(37.08, 126.9997), road = "가선", direction = "B기점 + A종점"),
            ),
        )
        assertEquals("양쪽", d.nearestAhead(GeoPoint(37.0, 127.0), 0.0)!!.shelter.name)
    }

    @Test
    fun noShelterPairSharesOneCoordinateAnymore() {
        // 원본 표준데이터에는 상·하행이 같은 좌표인 쉼터가 16곳(32행) 있었다. OSM 차로 기하로 나눠 넣었다.
        val dup = dir.shelters.groupBy { it.point }.filter { it.value.size > 1 }
        assertTrue("같은 좌표 쉼터: ${dup.keys}", dup.isEmpty())
    }

    @Test
    fun correctedPairsAreOnTheRightCarriageway() {
        // 일죽(중부선): 북쪽(하남 방향)으로 짝 사이를 지나면 하남 방향 쉼터가 오른쪽에 있어야 한다.
        val ilA = dir.shelters.first { it.name == "일죽" && it.direction == "통영기점 + 하남종점" }
        val ilB = dir.shelters.first { it.name == "일죽" && it.direction == "하남기점 + 통영종점" }
        val mid = GeoPoint((ilA.point.lat + ilB.point.lat) / 2, (ilA.point.lon + ilB.point.lon) / 2)
        assertEquals(Travel("중부선", "통영기점 + 하남종점"), dir.travelAt(mid, 325.0))
        assertEquals(Travel("중부선", "하남기점 + 통영종점"), dir.travelAt(mid, 145.0))
        // 일광: 원본 좌표가 고속도로에서 1.2km 떨어져 있었다. 이제 부산외곽순환고속도로 위(OSM 일광졸음쉼터 근처)다.
        val ilgwang = dir.shelters.filter { it.name == "일광" }
        assertTrue(ilgwang.all { Geo.distanceKm(it.point, GeoPoint(35.2814328, 129.2065787)) < 0.1 })
    }

    @Test
    fun directionIsFoundFromEitherCarriagewayNotJustTheMiddle() {
        val a = dir.shelters.first { it.name == "일죽" && it.direction == "통영기점 + 하남종점" }
        // 하남 방향 쉼터 바로 옆(차로 위, 쉼터에서 왼쪽으로 15m)을 북서쪽으로 지난다.
        val onLane = GeoPoint(a.point.lat + 0.00008, a.point.lon - 0.00014)
        assertEquals(Travel("중부선", "통영기점 + 하남종점"), dir.travelAt(onLane, 325.0))
        // 진행 방향이 10도쯤 틀어져 있어도 같은 답.
        assertEquals(Travel("중부선", "통영기점 + 하남종점"), dir.travelAt(onLane, 335.0))
        assertEquals(Travel("중부선", "통영기점 + 하남종점"), dir.travelAt(onLane, 315.0))
    }

    @Test
    fun everyPairInTheDataGivesAConsistentAnswerOrNone() {
        // 실제 데이터의 모든 짝: 한쪽 쉼터 옆을 양 방향으로 지나 보면, 답이 나올 때는 서로 반대 방향이어야 한다(같은 답이면 판단이 틀린 것).
        var decided = 0
        dir.shelters.groupBy { it.name to it.road }.values.filter { it.size == 2 && Geo.distanceKm(it[0].point, it[1].point) < 1.0 }.forEach { (a, b) ->
            val mid = GeoPoint((a.point.lat + b.point.lat) / 2, (a.point.lon + b.point.lon) / 2)
            val axis = Geo.bearingDeg(a.point, b.point)
            // 짝을 잇는 선에 수직인 두 방향이 길의 두 진행 방향에 가깝다.
            val t1 = dir.travelAt(mid, (axis + 90) % 360)
            val t2 = dir.travelAt(mid, (axis + 270) % 360)
            if (t1 != null && t2 != null) {
                decided++
                assertTrue("${a.name}: $t1 / $t2", t1 != t2)
            }
        }
        assertTrue("판단되는 짝이 충분해야 한다: $decided", decided >= 60)
    }
}
