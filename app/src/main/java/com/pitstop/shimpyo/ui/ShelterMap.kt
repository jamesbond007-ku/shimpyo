package com.pitstop.shimpyo.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.pitstop.shimpyo.core.Geo
import com.pitstop.shimpyo.core.GeoPoint
import com.pitstop.shimpyo.core.MapScope
import com.pitstop.shimpyo.core.MapViewport
import com.pitstop.shimpyo.core.MiniMap
import com.pitstop.shimpyo.core.Shelter
import com.pitstop.shimpyo.core.UiState
import com.pitstop.shimpyo.ui.theme.Pleos
import com.pitstop.shimpyo.ui.theme.PleosShadow
import com.pitstop.shimpyo.ui.theme.ShimpyoType
import com.pitstop.shimpyo.ui.theme.pleosSurface
import com.pitstop.shimpyo.ui.theme.smoothSpec

/**
 * 쉼터 미니맵.
 *
 * 졸음쉼터 공공데이터 좌표를 점으로 찍고, 그 위에 내 위치(진행 방향 화살표), 앞쪽 쉼터, 지금 권하는 쉼터나
 * 경유지로 넣은 쉼터를 얹는다. 바탕 지도가 없는 개요도라 인터넷·지도 키 없이 어디서나 똑같이 나온다.
 * 길안내는 하지 않는다(길안내는 Pleos 내비 앱의 몫). 누르면 내 주변 ↔ 전국 보기가 바뀐다.
 * 주행 중에도 보이는 화면이라 반복 애니메이션은 쓰지 않는다. 움직이는 것은 값(내 위치·영역)이 바뀔 때뿐이다.
 *
 * 영역·좌표 계산은 [MiniMap](core, 단위 테스트)에서 하고 여기서는 그리기만 한다.
 */
@Composable
internal fun ShelterMap(shelters: List<Shelter>, ui: UiState, modifier: Modifier = Modifier) {
    val c = Pleos.colors
    var requested by remember { mutableStateOf(MapScope.NEARBY) }
    val scope = MiniMap.effectiveScope(requested, ui.location)
    val canToggle = MiniMap.inKorea(ui.location)

    // 강조할 쉼터: 지금 권하는 곳이 먼저, 없으면 경유지.
    val offered = ui.offeredShelter
    val waypoint = ui.restStop
    val focus: Shelter? = offered?.shelter ?: waypoint
    val focusKm: Double? = offered?.distanceKm
        ?: waypoint?.let { w -> ui.location?.let { Geo.distanceKm(it, w.point) } }
    val focusTag = if (offered != null) "제안" else "경유지"
    // 권하는 쉼터·경유지까지 실제 도로 경로(받기 전이나 실패하면 null → 직선 점선).
    val road = focus?.let { f -> ui.location?.let { RoadRoute.routeFor(it, f.point) } }

    val surface = if (c.isDark) c.surfaceBasic else c.backgroundSecondary
    val description = buildString {
        append(if (scope == MapScope.NEARBY) "내 주변 졸음쉼터 지도" else "전국 졸음쉼터 지도")
        if (focus != null) {
            append(". $focusTag ${focus.spokenName}")
            focusKm?.let { append(" ${formatKm(it)}") }
        }
    }
    val measurer = rememberTextMeasurer()
    // 위치를 처음 받은 순간에는 차 표시를 (0,0)에서 끌고 오지 않고 그 자리에 바로 놓는다.
    val hadLocation = remember { booleanArrayOf(false) }
    val firstFix = ui.location != null && !hadLocation[0]
    hadLocation[0] = ui.location != null

    BoxWithConstraints(
        modifier
            .pleosSurface(surface, 40.dp, PleosShadow.L1, c.isDark)
            .clip(RoundedCornerShape(40.dp))
            .clickable(enabled = canToggle, role = Role.Button) {
                requested = if (scope == MapScope.NEARBY) MapScope.COUNTRY else MapScope.NEARBY
            }
            .semantics { contentDescription = description },
    ) {
        val aspect = if (maxHeight.value > 0f) (maxWidth / maxHeight).toDouble() else 1.0
        val target = MiniMap.viewport(
            scope,
            ui.location,
            focus?.point,
            ui.aheadShelters.map { it.shelter.point },
            aspect,
        )
        // 영역과 차 위치를 부드럽게 옮긴다(가상 주행에서 1초마다 위치가 들어와도 끊기지 않게).
        val centerLat by animateFloatAsState(target.center.lat.toFloat(), smoothSpec(), label = "mapLat")
        val centerLon by animateFloatAsState(target.center.lon.toFloat(), smoothSpec(), label = "mapLon")
        val half by animateFloatAsState(target.halfHeightKm.toFloat(), smoothSpec(), label = "mapHalf")
        val here = ui.location
        val carSpec: AnimationSpec<Float> = if (firstFix) snap() else smoothSpec()
        val carLat by animateFloatAsState((here?.lat ?: 0.0).toFloat(), carSpec, label = "carLat")
        val carLon by animateFloatAsState((here?.lon ?: 0.0).toFloat(), carSpec, label = "carLon")
        val halfKm = half.toDouble().coerceAtLeast(0.5)
        val view = MapViewport(GeoPoint(centerLat.toDouble(), centerLon.toDouble()), halfKm * aspect, halfKm)
        val car = here?.let { GeoPoint(carLat.toDouble(), carLon.toDouble()) }

        val country = scope == MapScope.COUNTRY
        val dot = c.textQuaternary
        val ink = c.textPrimary
        val rest = c.positive
        val labelBg = surface.copy(alpha = 0.9f)
        val aheadStyle = ShimpyoType.Small.copy(color = c.textSecondary)
        val focusStyle = ShimpyoType.Caption.copy(color = c.textPrimary)
        val scaleStyle = ShimpyoType.Small.copy(color = c.textTertiary)
        val scaleKm = MiniMap.scaleBarKm(view)

        Canvas(Modifier.fillMaxSize()) {
            fun at(p: GeoPoint): Offset {
                val (x, y) = view.project(p)
                return Offset((x * size.width).toFloat(), (y * size.height).toFloat())
            }

            // 0) 바탕 지도: 오픈스트리트맵 타일(인터넷이 있을 때만, 키 필요 없음). 없으면 개요도 그대로.
            with(MapTiles) { drawTiles(view, c.isDark, alpha = 0.85f) }

            // 1) 전국 졸음쉼터. 점이 모이면 고속도로망 모양이 드러난다.
            val dotRadius = (if (country) 2.5f else 4f).dp.toPx()
            for (s in shelters) {
                if (view.contains(s.point, marginRatio = 0.02)) drawCircle(dot, dotRadius, at(s.point))
            }

            // 이름표가 피해야 할 자리(표식)와 이미 놓인 이름표.
            val taken = mutableListOf<Rect>()
            fun reserve(p: Offset, radiusDp: Float) {
                val r = radiusDp.dp.toPx()
                taken += Rect(p.x - r, p.y - r, p.x + r, p.y + r)
            }

            // 2) 앞쪽 쉼터(주변 보기에서만, 이름만. 거리는 ‘쉼터 거리 맞히기’ 답이 되므로 적지 않는다).
            val aheadMarks = if (country) emptyList() else ui.aheadShelters.filter { it.shelter.name != focus?.name }
            for (a in aheadMarks) {
                val p = at(a.shelter.point)
                drawCircle(ink, 7.dp.toPx(), p)
                drawCircle(surface, 3.5.dp.toPx(), p)
                reserve(p, 9f)
            }

            // 3) 권하는 쉼터·경유지: 내 위치에서 점선으로 잇고 휴식 색으로 강조한다.
            val focusAt = focus?.let { at(it.point) }
            if (focusAt != null && road != null) {
                // 실제 도로 경로: 흰 테두리 위에 휴식 색 선.
                val path = Path().apply {
                    road.points.forEachIndexed { i, p -> val o = at(p); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
                }
                drawPath(path, surface, style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                drawPath(path, rest, style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
            if (focusAt != null) {
                if (road == null) car?.let {
                    drawLine(
                        rest.copy(alpha = 0.7f),
                        at(it),
                        focusAt,
                        strokeWidth = 4.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 12.dp.toPx())),
                    )
                }
                drawCircle(rest.copy(alpha = 0.18f), 18.dp.toPx(), focusAt)
                drawCircle(rest, 10.dp.toPx(), focusAt)
                drawCircle(surface, 4.dp.toPx(), focusAt)
                reserve(focusAt, 18f)
            }

            // 4) 내 위치. 진행 방향을 알면 화살표, 모르면 점.
            if (car != null && view.contains(car)) {
                val p = at(car)
                drawCircle(ink.copy(alpha = 0.10f), 26.dp.toPx(), p)
                val heading = ui.headingDeg
                if (heading == null) {
                    drawCircle(surface, 11.dp.toPx(), p)
                    drawCircle(ink, 8.dp.toPx(), p)
                } else {
                    val s = 15.dp.toPx()
                    val arrow = Path().apply {
                        moveTo(p.x, p.y - s)
                        lineTo(p.x + s * 0.72f, p.y + s * 0.78f)
                        lineTo(p.x, p.y + s * 0.38f)
                        lineTo(p.x - s * 0.72f, p.y + s * 0.78f)
                        close()
                    }
                    rotate(heading.toFloat(), pivot = p) {
                        drawPath(arrow, surface, style = Stroke(width = 6.dp.toPx()))
                        drawPath(arrow, ink)
                    }
                }
                reserve(p, 26f)
            }

            // 이름표: 권하는 쉼터가 먼저 자리를 잡고, 앞쪽 쉼터는 남는 자리에만. 겹치면 그 이름표는 생략한다.
            if (focus != null && focusAt != null) {
                val text = buildString {
                    append(focusTag).append(" · ").append(focus.spokenName)
                    if (road != null) {
                        // 도로 거리와 예상 시간(도로 경로를 받았을 때).
                        append(" · 도로 ").append(formatKm(road.meters / 1000.0))
                        append(" · ").append(((road.seconds / 60.0).coerceAtLeast(1.0)).toInt()).append("분")
                    } else {
                        focusKm?.let { append(" · ").append(formatKm(it)) }
                    }
                }
                pill(measurer, text, focusAt, focusStyle, labelBg, taken, gapDp = 22f, force = true)
            }
            for (a in aheadMarks) {
                pill(measurer, a.shelter.spokenName, at(a.shelter.point), aheadStyle, labelBg, taken)
            }

            // 5) 축척 막대(왼쪽 아래).
            val barPx = (scaleKm / (2 * view.halfWidthKm) * size.width).toFloat()
            val x0 = 28.dp.toPx()
            val y0 = size.height - 28.dp.toPx()
            drawLine(c.textTertiary, Offset(x0, y0), Offset(x0 + barPx, y0), 3.dp.toPx(), cap = StrokeCap.Round)
            drawLine(c.textTertiary, Offset(x0, y0 - 8.dp.toPx()), Offset(x0, y0), 3.dp.toPx(), cap = StrokeCap.Round)
            drawLine(c.textTertiary, Offset(x0 + barPx, y0 - 8.dp.toPx()), Offset(x0 + barPx, y0), 3.dp.toPx(), cap = StrokeCap.Round)
            val scaleLayout = measurer.measure(AnnotatedString("${scaleKm}km"), scaleStyle, maxLines = 1)
            drawText(scaleLayout, topLeft = Offset(x0 + barPx + 12.dp.toPx(), y0 - scaleLayout.size.height + 4.dp.toPx()))
        }

        Text(
            MiniMap.caption(scope, ui.location, shelters.size),
            style = ShimpyoType.Small,
            color = c.textTertiary,
            maxLines = 1,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 28.dp, top = 24.dp, end = 28.dp),
        )
        if (MapTiles.anyLoaded && scope == MapScope.NEARBY) {
            Text(
                MapTiles.attribution,
                style = ShimpyoType.Small,
                color = c.textTertiary,
                maxLines = 1,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 28.dp, top = 24.dp),
            )
        }
        if (canToggle) {
            Text(
                if (scope == MapScope.NEARBY) "눌러서 전국 보기" else "눌러서 내 주변 보기",
                style = ShimpyoType.Small,
                color = c.textTertiary,
                maxLines = 1,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 18.dp),
            )
        }
    }
}

/**
 * 점 둘레(위·아래·오른쪽·왼쪽 순)에 둥근 이름표를 그린다. 화면 밖으로 나가지 않게 안쪽으로 밀어 넣고,
 * [taken]에 있는 자리와 겹치지 않는 첫 자리를 쓴다. 모두 겹치면 그리지 않는다([force]면 위쪽에 그대로 그린다).
 * 그린 자리는 [taken]에 더한다.
 */
private fun DrawScope.pill(
    measurer: TextMeasurer,
    text: String,
    anchor: Offset,
    style: TextStyle,
    background: Color,
    taken: MutableList<Rect>,
    gapDp: Float = 16f,
    force: Boolean = false,
) {
    val layout = measurer.measure(AnnotatedString(text), style, maxLines = 1)
    val padH = 12.dp.toPx()
    val padV = 4.dp.toPx()
    val edge = 12.dp.toPx()
    val w = layout.size.width + padH * 2
    val h = layout.size.height + padV * 2
    val gap = gapDp.dp.toPx()
    // 위쪽 안내 문구와 아래쪽 축척·전환 안내 자리는 비워 둔다.
    val top = 60.dp.toPx()
    val bottom = (size.height - 56.dp.toPx() - h).coerceAtLeast(top)
    val maxX = (size.width - w - edge).coerceAtLeast(edge)
    fun rectAt(left: Float, y: Float): Rect {
        val l = left.coerceIn(edge, maxX)
        val t = y.coerceIn(top, bottom)
        return Rect(l, t, l + w, t + h)
    }
    // 위 → 아래 → 오른쪽 → 왼쪽 순서로 빈자리를 찾는다.
    val candidates = listOf(
        rectAt(anchor.x - w / 2f, anchor.y - gap - h),
        rectAt(anchor.x - w / 2f, anchor.y + gap),
        rectAt(anchor.x + gap, anchor.y - h / 2f),
        rectAt(anchor.x - gap - w, anchor.y - h / 2f),
    )
    val spot = candidates.firstOrNull { r -> taken.none { it.overlaps(r) } }
        ?: if (force) candidates.first() else return
    taken += spot
    drawRoundRect(background, spot.topLeft, spot.size, CornerRadius(h / 2f))
    drawText(layout, topLeft = Offset(spot.left + padH, spot.top + padV))
}

internal fun formatKm(km: Double): String = when {
    km < 1.0 -> "${(km * 1000).toInt()}m"
    km < 10.0 -> "%.1fkm".format(km)
    else -> "${km.toInt()}km"
}
