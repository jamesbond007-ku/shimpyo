package com.pitstop.shimpyo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import com.pitstop.shimpyo.ui.components.BlurSwap
import com.pitstop.shimpyo.ui.components.RollingNumber
import com.pitstop.shimpyo.ui.theme.LocalReduceMotion
import com.pitstop.shimpyo.ui.theme.Motion
import com.pitstop.shimpyo.ui.theme.introSpec
import com.pitstop.shimpyo.ui.theme.sheetSpec
import com.pitstop.shimpyo.ui.theme.smoothSpec
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pitstop.shimpyo.AppGraph
import com.pitstop.shimpyo.core.Backend
import com.pitstop.shimpyo.core.CabinPrep
import com.pitstop.shimpyo.core.NapCapsule
import com.pitstop.shimpyo.core.ShimpyoController
import com.pitstop.shimpyo.core.Phase
import com.pitstop.shimpyo.core.PlayMode
import com.pitstop.shimpyo.core.TeamPreference
import com.pitstop.shimpyo.core.Turn
import com.pitstop.shimpyo.core.RiskLevel
import com.pitstop.shimpyo.core.UiState
import com.pitstop.shimpyo.core.sim.SimVehicleGateway
import com.pitstop.shimpyo.ui.components.Chip
import com.pitstop.shimpyo.ui.components.LineTabs
import com.pitstop.shimpyo.ui.components.MonoSwitch
import com.pitstop.shimpyo.ui.components.PillButton
import com.pitstop.shimpyo.ui.components.PillStyle
import com.pitstop.shimpyo.ui.components.PleosLoading
import com.pitstop.shimpyo.ui.theme.Pleos
import com.pitstop.shimpyo.ui.theme.PleosColors
import com.pitstop.shimpyo.ui.theme.PleosShadow
import com.pitstop.shimpyo.ui.theme.ShimpyoType
import com.pitstop.shimpyo.ui.theme.pleosSurface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/*
 * 쉼표 메인 화면.
 *
 * 한 화면에 한 가지 이야기만 한다. 왼쪽의 계기(다이얼)는 운행 중엔 졸음 위험을, 휴식 중엔 남은 시간을 보여주고
 * 오른쪽은 지금 쉼표가 무엇을 하는지 한 문장으로 말한다. 카드·아이콘·칩을 걷어내고
 * 가는 선과 글자 굵기 차이만으로 위계를 만든다. 색은 위험 단계와 휴식, 두 가지 의미에만 쓴다.
 *
 * 시연·진단 조작은 운전 화면에서 빼서 오른쪽 위 '시연 도구'로 여는 패널에 모았다.
 * 버튼은 운전석(왼쪽)에 가까운 왼쪽 아래에 둔다.
 */

private val WideBreakpoint = 1280.dp

/** 시연판(demo)에만 시연 도구가 있다. 실제 작동판(live)은 같은 자리에 ‘설정’(같이하기 기본값·기록·SDK 진단)만 둔다. */
private val DemoTools = com.pitstop.shimpyo.BuildConfig.DEMO_TOOLS
private val ToolsTitle = if (DemoTools) "시연 도구" else "설정"

@Composable
fun ShimpyoScreen(graph: AppGraph) {
    val c = Pleos.colors
    val ui = graph.uiState
    val bg = screenBackground(c)
    val scope = rememberCoroutineScope()
    val openSpec = sheetSpec<Float>()

    // 시트 진행도 0(닫힘)~1(열림). 손가락 끌기와 스프링이 같은 값을 공유해서, 놓는 순간의 속도가 그대로 이어진다.
    val sheet = remember { Animatable(0f) }
    fun openTools() { scope.launch { sheet.animateTo(1f, openSpec) } }
    fun closeTools(velocity: Float = 0f) { scope.launch { sheet.animateTo(0f, openSpec, initialVelocity = velocity) } }
    val progress = sheet.value.coerceIn(0f, 1f)
    BackHandler(enabled = sheet.targetValue > 0f) { closeTools() }

    // 계기 자리에 미니맵을 띄울지. 쉼터를 권하거나 경유지로 가는 동안에는 저절로 지도로 바뀌고,
    // 머리글의 ‘지도 보기 / 계기 보기’로 직접 바꿀 수도 있다(자동 조건이 바뀌면 직접 고른 것은 풀린다).
    val autoMap = ui.offeredShelter != null ||
        (ui.restStop != null && (ui.phase == Phase.EN_ROUTE || ui.phase == Phase.WAITING_PARK))
    var mapChoice by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(autoMap) { mapChoice = null }
    val showMap = mapChoice ?: autoMap
    val toggleMap = { mapChoice = !showMap }

    // iOS 카드 시트처럼 뒤 화면이 살짝 작아지고, 모서리가 둥글어지고, 흐려지며 물러난다.
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val s = 1f - 0.05f * progress
                    scaleX = s
                    scaleY = s
                    shape = RoundedCornerShape((44f * progress).dp)
                    clip = progress > 0f
                }
                // 흐림은 시트를 열 때만 건다. 0이어도 효과 레이어가 생기면 그 안의 WebView(지도)가 그려지지 않는다.
                .then(if (progress > 0.001f) Modifier.blur((20f * progress).dp) else Modifier)
                .background(bg),
        ) {
            val wide = maxWidth >= WideBreakpoint && maxHeight >= 640.dp
            if (wide) {
                Column(Modifier.fillMaxSize().padding(start = 72.dp, end = 72.dp, top = 56.dp, bottom = 64.dp)) {
                    Header(graph, ui, showMap, toggleMap) { openTools() }
                    Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(600.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            val dialSize = minOf(560.dp, this@BoxWithConstraints.maxHeight - 360.dp).coerceAtLeast(200.dp)
                            DialOrMap(graph, ui, dialSize, minOf(600.dp, dialSize * 1.2f), showMap)
                        }
                        Spacer(Modifier.width(96.dp))
                        Narrative(graph, ui, Modifier.weight(1f))
                    }
                    Actions(graph, ui)
                }
            } else {
                // 좁은 화면(내비와 나눠 띄운 분할 화면, 세로 화면): 운전 중에 누를 버튼은 늘 아래에 보이게 고정하고
                // 계기와 문장만 그 위에서 스크롤한다. 단계 선은 빼서 한 화면에 들어오게 한다.
                Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 40.dp)) {
                    Header(graph, ui, showMap, toggleMap, compact = true) { openTools() }
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(32.dp),
                    ) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            // 동승자 패드가 뜨면 계기를 줄여 패드가 스크롤 아래로 밀리지 않게 한다.
                            val padShown = ui.playMode == PlayMode.TEAM && ui.listening && ui.turn != null
                            val dialSize = minOf(
                                if (padShown) 240.dp else 400.dp,
                                this@BoxWithConstraints.maxWidth - 80.dp,
                                this@BoxWithConstraints.maxHeight * 0.30f,
                            )
                            DialOrMap(graph, ui, dialSize, this@BoxWithConstraints.maxWidth - 80.dp, showMap)
                        }
                        Narrative(graph, ui, Modifier.fillMaxWidth(), compact = true)
                    }
                    Spacer(Modifier.height(16.dp))
                    Actions(graph, ui)
                }
            }
        }

        if (sheet.value > 0f || sheet.isRunning) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = progress }
                    .background(c.overlay)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { closeTools() },
            )
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= WideBreakpoint
                val panelWidth = if (wide) 640.dp else maxWidth - 48.dp
                val density = LocalDensity.current
                val travel = with(density) { (panelWidth + 48.dp).toPx() }
                val drag = rememberDraggableState { delta ->
                    // 왼쪽(열리는 방향)으로 더 끌면 고무줄처럼 저항한다.
                    val raw = sheet.value - delta / travel
                    val next = if (raw > 1f) 1f + (raw - 1f) * 0.25f else raw
                    scope.launch { sheet.snapTo(next.coerceIn(0f, 1.04f)) }
                }
                ToolsPanel(
                    graph,
                    ui,
                    onClose = { closeTools() },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .graphicsLayer { translationX = (1f - sheet.value) * travel }
                        .draggable(
                            state = drag,
                            orientation = Orientation.Horizontal,
                            onDragStopped = { velocity ->
                                val v = -velocity / travel
                                if (sheet.value < 0.72f || v < -1.2f) closeTools(v) else openTools()
                            },
                        )
                        .padding(24.dp)
                        .width(panelWidth)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

private fun screenBackground(c: PleosColors): Color = if (c.isDark) c.backgroundSecondary else c.backgroundPrimary

// ── 머리글 ─────────────────────────────────────────────────────────────────

@Composable
private fun Header(
    graph: AppGraph,
    ui: UiState,
    showMap: Boolean,
    onToggleMap: () -> Unit,
    compact: Boolean = false,
    onOpenTools: () -> Unit,
) {
    val c = Pleos.colors
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("쉼표", style = ShimpyoType.Word, color = c.textPrimary, modifier = Modifier.semantics { heading() })
        val gap = if (compact) 16.dp else 28.dp
        Row(Modifier.weight(1f).padding(start = gap), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            // 좁은 화면에서는 머리글이 넘치지 않게 시연용 표시를 뺀다.
            if (DemoTools && graph.demoMode && !compact) {
                Text("시연 배속", style = ShimpyoType.Caption, color = c.textTertiary, maxLines = 1)
                Spacer(Modifier.width(gap))
            }
            val resting = ui.phase == Phase.RESTING
            Box(Modifier.size(8.dp).background(if (resting) c.positive else c.textTertiary, CircleShape))
            Spacer(Modifier.width(12.dp))
            Text(vehicleStatus(ui), style = ShimpyoType.Caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (ui.passengerPresent == true || ui.playMode == PlayMode.TEAM) {
                Spacer(Modifier.width(gap))
                Text(
                    if (ui.playMode == PlayMode.TEAM) "같이하기" else "동승자 있음",
                    style = ShimpyoType.Caption,
                    color = c.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        // 여기서부터는 누를 수 있는 것. 상태 글자보다 진하게 두고 구분선으로 나눈다.
        Spacer(Modifier.width(gap))
        Box(Modifier.width(2.dp).height(24.dp).background(c.dimTertiary))
        HeaderAction(if (showMap) "계기 보기" else "지도 보기", onToggleMap, gap)
        HeaderAction(ToolsTitle, onOpenTools, gap)
    }
}

@Composable
private fun HeaderAction(label: String, onClick: () -> Unit, gap: Dp = 28.dp) {
    Text(
        label,
        style = ShimpyoType.Caption,
        color = Pleos.colors.textSecondary,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = gap, top = 14.dp, bottom = 14.dp),
    )
}

private fun vehicleStatus(ui: UiState): String = when (ui.parked) {
    true -> "주차"
    false -> if (ui.phase == Phase.IDLE) "주행" else "주행 ${ui.drivingMinutes}분"
    null -> "차량 상태 확인 중"
}

// ── 다이얼 ─────────────────────────────────────────────────────────────────

/**
 * 계기(다이얼)와 쉼터 미니맵이 같은 자리를 번갈아 쓴다. 다이얼은 늘 그려 둔 채 투명도만 바꿔서
 * 지도에서 돌아와도 다이얼의 첫 등장 연출이 다시 나오지 않는다.
 */
@Composable
private fun DialOrMap(graph: AppGraph, ui: UiState, size: Dp, mapWidth: Dp, showMap: Boolean) {
    val swapRaw by animateFloatAsState(if (showMap) 1f else 0f, smoothSpec(), label = "dialMap")
    val t = swapRaw.coerceIn(0f, 1f)
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier.graphicsLayer {
                alpha = 1f - t
                val s = 1f - 0.06f * t
                scaleX = s
                scaleY = s
            },
        ) { Dial(ui, size) }
        if (showMap || t > 0.001f) {
            ShelterMap(
                graph.shelters,
                ui,
                Modifier
                    .width(mapWidth)
                    .height(size)
                    // 다 나타난 뒤에는 효과 레이어를 떼어 낸다(레이어 안의 WebView 지도가 그려지지 않는 문제).
                    .then(
                        if (t < 0.999f) Modifier.graphicsLayer {
                            alpha = t
                            val s = 0.94f + 0.06f * t
                            scaleX = s
                            scaleY = s
                        } else Modifier,
                    ),
            )
        }
    }
}

/**
 * 계기판처럼 가는 눈금 + 2dp 트랙 + 6dp 진행 호.
 * 운행 중: 270° 졸음 위험(0~100). 휴식 중: 360° 남은 시간. 숫자는 고정폭으로 흔들리지 않게 한다.
 */
@Composable
private fun Dial(ui: UiState, size: Dp) {
    val c = Pleos.colors
    val reduce = LocalReduceMotion.current
    val resting = ui.phase == Phase.RESTING
    val idle = ui.phase == Phase.IDLE

    // 휴식 전체 길이는 들어온 남은 시간의 최댓값(시연 배속에서도 비율이 맞도록). 화면 갱신을 일으키지 않게 배열에 보관.
    val restMax = remember { IntArray(1) }
    if (!resting) restMax[0] = 0 else if (ui.restRemainingSec > restMax[0]) restMax[0] = ui.restRemainingSec

    val target = when {
        resting -> if (restMax[0] > 0) ui.restRemainingSec.toFloat() / restMax[0] else 1f
        idle -> 0f
        else -> ui.score.coerceIn(0, 100) / 100f
    }
    val fraction by animateFloatAsState(target, smoothSpec(), label = "dial")
    // 위험 계기(270°) ↔ 휴식 시계(360°)를 끊지 않고 이어서 바꾼다. 눈금도 같은 값으로 펼쳐진다.
    val morph by animateFloatAsState(if (resting) 1f else 0f, smoothSpec(), label = "morph")
    val arc by animateColorAsState(if (resting) c.positive else levelColor(ui.level, c), smoothSpec(), label = "arcColor")

    // 앱을 처음 열 때 한 번만: 눈금이 시계 방향으로 차례로 켜지고 트랙이 그려진다.
    val intro = remember { Animatable(if (reduce) 1f else 0f) }
    val introAnim = introSpec<Float>(1400, 150)
    LaunchedEffect(Unit) { intro.animateTo(1f, introAnim) }

    // 주차 후 휴식 중에만: 4초 들이쉬고 6초 내쉬는 호흡 안내. 운행 중에는 반복 모션을 쓰지 않는다.
    val breatheOn = resting && ui.parked == true && !reduce
    // 반복 애니메이션은 호흡 안내가 켜졌을 때만 만든다. 늘 돌려 두면 주행 내내 계기를 1초에 60번 다시 그린다.
    val breathPhase = if (breatheOn) breathingPhase() else 0f
    val breathAmount by animateFloatAsState(if (breatheOn) 1f else 0f, smoothSpec(), label = "breathAmount")

    val tickMinor = c.dimSecondary
    val tickMajor = c.dimTertiary
    val track = c.dimSecondary
    val glow = c.positive
    val scale = (size.value / 560f).coerceAtLeast(0.25f)

    Box(
        Modifier.size(size).semantics {
            contentDescription = if (resting) {
                "남은 휴식 ${ui.restRemainingSec / 60}분 ${ui.restRemainingSec % 60}초"
            } else {
                "졸음 위험 ${ui.level.label}, 100점 중 ${ui.score}점"
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val px = this.size.minDimension
            val r = px / 2f - 44.dp.toPx() * scale
            val m = morph.coerceIn(0f, 1f)
            val start = 135f + (-90f - 135f) * m
            val sweep = 270f + 90f * m
            val count = 60
            val shown = intro.value

            val b = breathPhase * breathAmount
            if (breathAmount > 0.001f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(glow.copy(alpha = 0.16f * b + 0.04f * breathAmount), Color.Transparent),
                        center = center,
                        radius = r * (0.92f + 0.12f * b),
                    ),
                    radius = r * (0.92f + 0.12f * b),
                    center = center,
                )
            }

            for (i in 0..count) {
                val t = i.toFloat() / count
                if (t > shown) break
                // 360°가 되면 마지막 눈금이 첫 눈금과 겹치므로 서서히 지운다.
                val fadeLast = if (i == count) 1f - m else 1f
                val appear = ((shown - t) * 12f).coerceIn(0f, 1f)
                val a = Math.toRadians((start + sweep * t).toDouble())
                val major = i % 5 == 0
                val r1 = r + 26.dp.toPx() * scale
                val r2 = r + (if (major) 40.dp else 34.dp).toPx() * scale
                val base = if (major) tickMajor else tickMinor
                drawLine(
                    color = base.copy(alpha = base.alpha * fadeLast * appear),
                    start = Offset(center.x + r1 * cos(a).toFloat(), center.y + r1 * sin(a).toFloat()),
                    end = Offset(center.x + r2 * cos(a).toFloat(), center.y + r2 * sin(a).toFloat()),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            val topLeft = Offset(center.x - r, center.y - r)
            val arcSize = Size(r * 2, r * 2)
            drawArc(track, start, sweep * shown, false, topLeft, arcSize, style = Stroke(2.dp.toPx()))
            val value = fraction.coerceIn(0f, 1f) * shown
            if (value > 0.003f) {
                drawArc(arc, start, sweep * value, false, topLeft, arcSize, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                alpha = intro.value
                translationY = (1f - intro.value) * 24.dp.toPx()
            },
        ) {
            val numberSize = (if (resting) 136f else 168f) * scale
            val number = ShimpyoType.Dial.copy(fontSize = numberSize.sp, lineHeight = numberSize.sp)
            BlurSwap(resting, contentAlignment = Alignment.Center) { isRest ->
                when {
                    isRest -> RollingNumber("%d:%02d".format(ui.restRemainingSec / 60, ui.restRemainingSec % 60), number, c.textPrimary)
                    idle -> RollingNumber("0", number, c.textQuaternary)
                    else -> RollingNumber("${ui.score}", number, c.textPrimary)
                }
            }
            Spacer(Modifier.height(20.dp * scale))
            val caption = when {
                resting -> if (breatheOn) "천천히 숨 쉬어요" else "남은 휴식"
                idle -> "졸음 위험"
                else -> "졸음 위험 ${ui.level.label}"
            }
            BlurSwap(caption, contentAlignment = Alignment.Center) { text ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!idle && !resting) {
                        Box(Modifier.size(8.dp).background(arc, CircleShape))
                        Spacer(Modifier.width(12.dp))
                    }
                    // 계기가 작아지면(분할 화면) 설명 글자도 같이 줄여 호와 겹치지 않게 한다(최소 18sp).
                    val captionSize = (26f * scale).coerceIn(18f, 26f)
                    Text(text, style = ShimpyoType.UnitLabel.copy(fontSize = captionSize.sp, lineHeight = (captionSize * 1.3f).sp), color = c.textTertiary)
                }
            }
        }
    }
}

/** 4초 들이쉬고 6초 내쉬는 호흡 위상(0~1). 휴식 중에만 부른다. */
@Composable
private fun breathingPhase(): Float {
    val breath = rememberInfiniteTransition(label = "breath")
    val phase by breath.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 10_000
                0f at 0 using Motion.EaseOutExpressive
                1f at 4_000 using LinearOutSlowInEasing
                0f at 10_000
            },
        ),
        label = "breathPhase",
    )
    return phase
}

// ── 오른쪽 이야기 ──────────────────────────────────────────────────────────

@Composable
private fun Narrative(graph: AppGraph, ui: UiState, modifier: Modifier = Modifier, compact: Boolean = false) {
    val c = Pleos.colors
    // 첫 등장: 제목 → 문장 → 단계 → 수치 순으로 70ms씩 늦게 올라온다. 이후엔 바뀐 것만 움직인다.
    val reduce = LocalReduceMotion.current
    val intro = remember { Animatable(if (reduce) 1f else 0f) }
    val introAnim = introSpec<Float>(1100, 250)
    LaunchedEffect(Unit) { intro.animateTo(1f, introAnim) }
    fun Modifier.stagger(order: Int) = graphicsLayer {
        val p = ((intro.value - order * 0.07f) / (1f - 0.21f)).coerceIn(0f, 1f)
        alpha = p
        translationY = (1f - p) * 28.dp.toPx()
    }

    Column(modifier) {
        BlurSwap(headline(ui), Modifier.stagger(0).semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(it, style = ShimpyoType.Phase, color = c.textPrimary)
        }
        Spacer(Modifier.height(28.dp))
        // 쉼표가 지금 한 말. 퀴즈 문제나 졸림 척도 설명처럼 운전자가 다시 볼 수 있어야 해서 자르지 않는다.
        // 긴 문장(무입력 출발 인사는 130자 남짓)은 글자를 한 단계씩 줄여 5줄 안에 담는다.
        BlurSwap(ui.lastSpoken.ifEmpty { guidance(ui) }, Modifier.heightIn(min = 144.dp).stagger(1)) {
            val style = leadStyleFor(it)
            Text(it, style = style, color = c.textSecondary, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 760.dp))
        }
        Spacer(Modifier.height(24.dp))
        val reply: Pair<String, String?>? = when {
            ui.listening -> (ui.turn?.let { "${it.label} 차례 · 듣고 있어요" } ?: "듣고 있어요") to null
            ui.lastHeard.isNotEmpty() -> ui.lastHeard to ui.lastLatencyMs?.let { "%.1f초 만에 대답".format(it / 1000.0) }
            else -> null
        }
        BlurSwap(reply, Modifier.heightIn(min = 36.dp).stagger(1)) { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    r == null -> Spacer(Modifier.height(36.dp))
                    ui.listening && r.second == null && r.first.endsWith("듣고 있어요") -> {
                        PleosLoading(size = 32.dp, color = c.textTertiary, description = r.first)
                        Spacer(Modifier.width(16.dp))
                        Text(r.first, style = ShimpyoType.UnitLabel, color = c.textTertiary)
                    }
                    else -> {
                        Text("“${r.first}”", style = ShimpyoType.UnitLabel, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        r.second?.let {
                            Spacer(Modifier.width(16.dp))
                            Text(it, style = ShimpyoType.UnitLabel, color = c.textTertiary)
                        }
                    }
                }
            }
        }
        // 같이하기: 동승자는 말 대신 화면을 눌러 답할 수도 있다(운전자 차례에 누르면 반칙).
        if (ui.playMode == PlayMode.TEAM && ui.listening && ui.turn != null) {
            Spacer(Modifier.height(20.dp))
            PassengerPad(graph, ui)
        }
        if (!compact) {
            Spacer(Modifier.height(56.dp))
            Box(Modifier.stagger(2)) { Journey(ui.phase) }
        }
        Spacer(Modifier.height(48.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).stagger(3).background(c.dimSecondary))
        Spacer(Modifier.height(36.dp))
        Box(Modifier.stagger(3)) { Facts(ui) }
    }
}

/**
 * 같이하기 때 동승자가 화면으로 답하는 패드. O/X와 숫자(계산·구구단·숫자 거꾸로·거리 맞히기)를 보낼 수 있다.
 * 운전석에서 멀리 떨어진 동승자를 위한 것이라 운전자 차례에는 흐리게 두고, 그때 보내면 반칙으로 센다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PassengerPad(graph: AppGraph, ui: UiState) {
    val c = Pleos.colors
    var entry by remember { mutableStateOf("") }
    val passengerTurn = ui.turn == Turn.PASSENGER
    fun send(text: String) {
        graph.controller.passengerTouchAnswer(text)
        entry = ""
    }
    Column(Modifier.fillMaxWidth()) {
        // 바로 위 줄에 ‘○○ 차례 · 듣고 있어요’가 있으니 여기서는 패드를 어떻게 쓰는지만 말한다.
        Text(
            if (passengerTurn) "동승자분은 눌러서 답해도 돼요" else "지금은 운전자분 차례라 누르면 반칙이에요",
            style = ShimpyoType.Small,
            color = if (passengerTurn) c.textPrimary else c.textTertiary,
        )
        Spacer(Modifier.height(12.dp))
        // 1줄: 답 버튼(맞아·틀려·패스)과 숫자 보내기. 2줄: 숫자 키. 줄이 늘 같은 자리에 있어 찾기 쉽다.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("O 맞아", false, { send("맞아") })
            Chip("X 틀려", false, { send("틀려") })
            Chip("패스", false, { send("") })
            Spacer(Modifier.width(16.dp))
            Chip("지우기", false, { entry = entry.dropLast(1) })
            Chip(if (entry.isEmpty()) "숫자 보내기" else "$entry 보내기", entry.isNotEmpty(), { if (entry.isNotEmpty()) send(entry) })
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (d in listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0)) {
                Chip("$d", false, { entry = (entry + d).take(6) }, Modifier.width(64.dp), horizontalPadding = 0.dp)
            }
        }
    }
}

/** 쉼표가 지나는 다섯 단계. 지금 단계를 나타내는 점이 선을 따라 미끄러지고, 이름표가 따라간다. */
@Composable
private fun Journey(phase: Phase) {
    val c = Pleos.colors
    val steps = listOf("지켜보기", "대화", "쉼터 제안", "이동", "휴식")
    val current = when (phase) {
        Phase.IDLE -> -1
        Phase.MONITORING -> 0
        Phase.TALKING -> 1
        Phase.OFFERING -> 2
        Phase.EN_ROUTE, Phase.WAITING_PARK -> 3
        Phase.RESTING, Phase.WAKING -> 4
    }
    val posRaw by animateFloatAsState(current.coerceAtLeast(0).toFloat(), smoothSpec(), label = "journeyPos")
    val activeRaw by animateFloatAsState(if (current >= 0) 1f else 0f, smoothSpec(), label = "journeyActive")
    // 스프링이 끝에서 살짝 넘치더라도 점이 선 밖으로 나가지 않게 묶는다.
    val pos = posRaw.coerceIn(0f, (steps.size - 1).toFloat())
    val active = activeRaw.coerceIn(0f, 1f)
    val ink = c.textPrimary
    val line = c.dimSecondary
    val ring = c.dimTertiary
    val halo = c.dimSecondary
    val bg = screenBackground(c)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .semantics { contentDescription = if (current < 0) "운행 전" else "다섯 단계 중 ${current + 1}번째, ${steps[current]}" },
    ) {
        val w = maxWidth
        Canvas(Modifier.fillMaxWidth().height(16.dp)) {
            val y = this.size.height / 2f
            val step = this.size.width / (steps.size - 1)
            val head = step * pos
            drawLine(line, Offset(0f, y), Offset(this.size.width, y), 2.dp.toPx())
            if (active > 0f && pos > 0f) drawLine(ink.copy(alpha = ink.alpha * active), Offset(0f, y), Offset(head, y), 2.dp.toPx())
            for (i in steps.indices) {
                val p = Offset(step * i, y)
                // 지나간 정도(0~1)에 따라 빈 고리 → 작은 점으로 자연스럽게 바뀐다.
                val passed = ((pos - i + 1f).coerceIn(0f, 1f)) * active
                drawCircle(bg, 8.dp.toPx(), p)
                drawCircle(ring.copy(alpha = ring.alpha * (1f - passed)), 7.dp.toPx(), p, style = Stroke(2.dp.toPx()))
                if (passed > 0f) drawCircle(ink.copy(alpha = ink.alpha * passed), 5.dp.toPx() * passed, p)
            }
            if (active > 0f) {
                val p = Offset(head, y)
                drawCircle(halo.copy(alpha = halo.alpha * active), 16.dp.toPx() * active, p)
                drawCircle(ink, 8.dp.toPx() * active, p)
            }
        }
        if (current >= 0) {
            val labelWidth = 160.dp
            val x = w * (pos / (steps.size - 1))
            val offset = (x - labelWidth / 2).coerceIn(0.dp, w - labelWidth)
            val align = when {
                pos < 0.5f -> Alignment.TopStart
                pos > steps.lastIndex - 0.5f -> Alignment.TopEnd
                else -> Alignment.TopCenter
            }
            BlurSwap(steps[current], Modifier.padding(start = offset, top = 32.dp).width(labelWidth), contentAlignment = align) {
                Text(it, style = ShimpyoType.Small, color = c.textPrimary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Facts(ui: UiState) {
    val ratio = ui.latencyRatio?.let { "%.1f".format(it) to "배" }
        ?: ui.baselineMs?.let { "%.1f".format(it / 1000.0) to "초 기준" }
        ?: ("–" to "")
    Row(Modifier.fillMaxWidth()) {
        Fact("연속 주행", "${ui.drivingMinutes}", "분", Modifier.weight(1f))
        Fact("대답 속도", ratio.first, ratio.second, Modifier.weight(1f))
        // 세 번째 칸은 지금 가장 의미 있는 값 하나: 경유 쉼터 > 졸림 자가평가(KSS) > 계획한 휴식 > 지난밤 수면 > 쉼 제안 거절
        val planned = ui.plannedRestAtMinute
        when {
            ui.restStopName != null -> Fact("경유 쉼터", ui.restStopName, "", Modifier.weight(1f), rolling = false)
            ui.playMode == PlayMode.TEAM && ui.phase != Phase.IDLE -> Fact("팀 점수", "${ui.teamScore}", "점", Modifier.weight(1f))
            ui.kss != null -> Fact("졸림 정도", "${ui.kss}", "/ 9", Modifier.weight(1f))
            planned != null && ui.phase != Phase.IDLE ->
                Fact("계획한 휴식까지", "${(planned - ui.drivingMinutes).coerceAtLeast(0)}", "분", Modifier.weight(1f))
            ui.sleepHours != null -> Fact(
                when {
                    ui.sleepSource == null -> "지난 24시간 수면"
                    ui.cabinPrep != null && ui.cabinPrep != CabinPrep.NONE -> "지난밤 수면 · 워치 · 실내 ${ui.cabinPrep.label}"
                    else -> "지난밤 수면 · 워치"
                },
                formatSleep(ui.sleepHours), "시간", Modifier.weight(1f),
            )
            else -> Fact("쉼 제안", "${ui.declinedOffers}", "회 거절", Modifier.weight(1f))
        }
    }
}

private fun formatSleep(h: Double): String = if (h % 1.0 == 0.0) "${h.toInt()}" else "%.1f".format(h)

@Composable
private fun Fact(label: String, value: String, unit: String, modifier: Modifier = Modifier, rolling: Boolean = true) {
    val c = Pleos.colors
    Column(modifier.padding(end = 24.dp)) {
        BlurSwap(label) { Text(it, style = ShimpyoType.Small, color = c.textTertiary) }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            if (rolling) {
                RollingNumber(value, ShimpyoType.Fact, c.textPrimary)
            } else {
                BlurSwap(value, Modifier.weight(1f, fill = false)) {
                    Text(it, style = ShimpyoType.Fact, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                BlurSwap(unit) { Text(it, style = ShimpyoType.UnitLabel, color = c.textTertiary, modifier = Modifier.padding(bottom = 4.dp)) }
            }
        }
    }
}

// ── 행동 ───────────────────────────────────────────────────────────────────

private enum class ActionSet { Idle, Active, Resting }

@Composable
private fun Actions(graph: AppGraph, ui: UiState) {
    val c = Pleos.colors
    val set = when (ui.phase) {
        Phase.IDLE -> ActionSet.Idle
        Phase.RESTING, Phase.WAKING -> ActionSet.Resting
        else -> ActionSet.Active
    }
    // 버튼 묶음이 바뀔 때도 흐림 전환으로 갈아 끼운다. 같은 묶음 안의 활성/비활성은 색만 스프링으로 바뀐다.
    // 좁은 화면(분할 화면 등)에서는 버튼이 폭을 나눠 쓰게 해서 마지막 버튼이 잘리지 않게 한다.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val narrow = maxWidth < 760.dp
    BlurSwap(set, Modifier.fillMaxWidth()) { s ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val share = if (narrow) Modifier.weight(1f) else Modifier
            when (s) {
                ActionSet.Idle -> {
                    PillButton("운행 시작", { graph.controller.startTrip() }, share, style = PillStyle.Filled, compact = narrow)
                    // 실제 작동판인데 차량·내비 서비스를 못 찾아 시뮬레이터로 돌고 있으면 숨기지 않고 경고색으로 알린다.
                    val offline = !DemoTools && (ui.vehicleBackend == Backend.SIM || ui.naviBackend == Backend.SIM)
                    if (offline) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "차량·내비 연결 안 됨: 시트·공조 제어와 쉼터 경유지가 동작하지 않아요",
                            style = ShimpyoType.Caption, color = c.negative, modifier = if (narrow) Modifier.weight(1f) else Modifier,
                        )
                    } else if (!narrow) {
                        Spacer(Modifier.width(8.dp))
                        // 자동 시작은 기어·주차 신호를 받을 때만 된다. 신호가 없으면 그렇게 말하지 않는다.
                        Text(if (DemoTools || ui.parked == null) "대답은 말로 하면 돼요" else "출발하면 자동으로 시작해요 · 대답은 말로 하면 돼요", style = ShimpyoType.Caption, color = c.textTertiary)
                    }
                }
                ActionSet.Resting -> {
                    PillButton("휴식 마치기", { graph.controller.cancelRest() }, share, style = PillStyle.Filled, compact = narrow)
                    if (!narrow) Spacer(Modifier.weight(1f))
                    PillButton("운행 종료", { graph.controller.endTrip() }, share, style = PillStyle.Quiet, compact = narrow)
                }
                ActionSet.Active -> {
                    val canCancel = ui.phase == Phase.EN_ROUTE || ui.phase == Phase.WAITING_PARK
                    PillButton("지금 쉬기", { graph.controller.requestRestNow() }, share, style = PillStyle.Filled, enabled = ui.phase != Phase.WAITING_PARK, compact = narrow)
                    // 늘 흐린 버튼으로 두면 무엇을 누를 수 있는지 헷갈려서, 쉼터로 가는 중일 때만 보인다.
                    if (canCancel) PillButton("휴식 취소", { graph.controller.cancelRest() }, share, compact = narrow)
                    if (!narrow) Spacer(Modifier.weight(1f))
                    PillButton("운행 종료", { graph.controller.endTrip() }, share, style = PillStyle.Quiet, compact = narrow)
                }
            }
        }
    }
    }
}

// ── 시연 도구 패널 ─────────────────────────────────────────────────────────

private enum class ToolTab(val label: String) { Controls(if (DemoTools) "조작" else "설정"), Log("기록"), Diag("SDK 진단") }

@Composable
private fun ToolsPanel(graph: AppGraph, ui: UiState, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val c = Pleos.colors
    var tab by remember { mutableStateOf(ToolTab.Controls) }
    val move = smoothSpec<IntOffset>()
    val fade = smoothSpec<Float>()
    Column(
        modifier
            .pleosSurface(if (c.isDark) c.surfaceBasic else c.backgroundSecondary, 40.dp, PleosShadow.L3, c.isDark)
            // 패널 안 터치가 뒤의 배경(닫기)으로 새지 않게 막는다.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(48.dp),
    ) {
        // 끌어서 닫을 수 있다는 표시
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 48.dp, height = 5.dp).background(c.dimTertiary, RoundedCornerShape(3.dp)))
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(ToolsTitle, style = ShimpyoType.Lead.copy(fontSize = 36.sp, lineHeight = 44.sp), color = c.textPrimary)
                // 팀원끼리 같은 판인지 바로 알 수 있게 버전·빌드를 적는다.
                Text(
                    "v${com.pitstop.shimpyo.BuildConfig.VERSION_NAME} · 빌드 ${com.pitstop.shimpyo.BuildConfig.BUILD_ID}",
                    style = ShimpyoType.Small,
                    color = c.textTertiary,
                )
            }
            Text(
                "닫기",
                style = ShimpyoType.UnitLabel,
                color = c.textTertiary,
                modifier = Modifier.heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClose).padding(start = 24.dp, top = 11.dp, bottom = 11.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        LineTabs(ToolTab.entries.map { it.label to it }, tab, { tab = it })
        Spacer(Modifier.height(12.dp))
        // 탭 내용은 고른 방향으로 밀려 들어온다.
        AnimatedContent(
            targetState = tab,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                (slideInHorizontally(move) { w -> if (forward) w / 4 else -w / 4 } + fadeIn(fade)) togetherWith
                    (slideOutHorizontally(move) { w -> if (forward) -w / 4 else w / 4 } + fadeOut(fade))
            },
            label = "toolTab",
        ) { t ->
            when (t) {
                ToolTab.Controls -> if (DemoTools) Controls(graph, ui, Modifier.fillMaxSize()) else LiveSettings(graph, ui, Modifier.fillMaxSize())
                ToolTab.Log -> LogList(ui, Modifier.fillMaxSize())
                ToolTab.Diag -> DiagList(graph, Modifier.fillMaxSize())
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Controls(graph: AppGraph, ui: UiState, modifier: Modifier = Modifier) {
    val c = Pleos.colors
    Column(modifier.verticalScroll(rememberScrollState())) {
        ListRow("시연 배속", trailingText = "1초 = 1분") { MonoSwitch(graph.demoMode, { graph.applyDemoMode(it) }) }

        Group("시각") {
            listOf<Pair<String, Int?>>("지금" to null, "새벽 2시" to 2, "오후 2시" to 14, "오전 10시" to 10).forEach { (label, hour) ->
                Chip(label, graph.demoHour == hour, { graph.demoHour = hour })
            }
        }
        Group("말놀이 바로 하기 (위험도와 상관없이)") {
            ShimpyoController.DemoGame.entries.forEach { g ->
                // 지금 하고 있는 놀이는 검정(선택)으로 보인다. 놀이가 끝나면 다시 비워진다.
                val playing = ui.phase == Phase.TALKING && ui.talkTitle == g.gameTitle
                Chip(g.label, playing, { graph.controller.demoStartGame(g) })
            }
        }
        Text(
            when {
                ui.phase == Phase.IDLE -> "대답 버튼은 운행을 시작한 뒤에 눌러 주세요."
                ui.pendingAnswer != null -> "받아 둔 대답: ${ui.pendingAnswer} · 지금 질문이 끝나면 바로 들어가요"
                ui.listening -> "지금 듣는 중 · 누르면 바로 들어가요"
                else -> "언제 눌러도 돼요 · 다음 질문이 끝나면 들어가요"
            },
            style = ShimpyoType.Small.copy(color = if (ui.pendingAnswer != null || ui.listening) c.positive else c.textTertiary),
            modifier = Modifier.padding(top = 16.dp),
        )
        Group("운전자 대답") {
            Chip("정답 말하기", false, { graph.controller.injectCorrectAnswer() })
            Chip("빠른 대답", false, { graph.controller.injectResponse("응", 1_000) })
            Chip("느린 대답", false, { graph.controller.injectResponse("응", 3_000) })
            Chip("무응답", false, { graph.controller.injectResponse("", null) })
            Chip("“응, 쉬었다 가자”", false, { graph.controller.injectResponse("응 쉬었다 가자", 2_500) })
            Chip("“아니, 괜찮아”", false, { graph.controller.injectResponse("아니 괜찮아", 2_500) })
            Chip("“같이 할게”", false, { graph.controller.injectResponse("같이 할게", 1_500) })
            Chip("“혼자 할게”", false, { graph.controller.injectResponse("혼자 할게", 1_500) })
        }
        Group("수면 점검 대답") {
            Chip("“세 시간 잤어”", false, { graph.controller.injectResponse("세 시간", 1_500) })
            Chip("“일곱 시간”", false, { graph.controller.injectResponse("일곱 시간", 1_200) })
            Chip("“새벽 네 시”", false, { graph.controller.injectResponse("새벽 네 시", 1_500) })
            Chip("졸림 8", false, { graph.controller.injectResponse("8", 2_000) })
            Chip("졸림 3", false, { graph.controller.injectResponse("3", 1_200) })
        }
        Group("차량") {
            Chip("주행", graph.manualParked == false, { graph.setParked(false) })
            Chip("주차", graph.manualParked == true, { graph.setParked(true) })
            if (graph.pleosVehicle != null) Chip("자동", graph.manualParked == null, { graph.setParked(null) })
        }
        Group("동승자 (착좌 센서)") {
            Chip("혼자", graph.manualPassenger == false, { graph.setPassenger(false) })
            Chip("동승자 있음", graph.manualPassenger == true, { graph.setPassenger(true) })
            if (graph.pleosVehicle != null) Chip("자동", graph.manualPassenger == null, { graph.setPassenger(null) })
        }
        Group("같이하기 기본값 (주차 중에만 변경)") {
            TeamPreference.entries.forEach { p ->
                Chip(p.label, ui.teamPreference == p, { graph.applyTeamPreference(p) })
            }
        }
        if (ui.parked != true) {
            Text(
                "주차(P단) 중에만 바꿀 수 있어요. 주행 중에는 “같이 할게”, “혼자 할게”라고 말하면 바로 바뀌어요.",
                style = ShimpyoType.Small,
                color = Pleos.colors.textTertiary,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Group("워치 수면 기록 (다음 운행부터)") {
            Chip("없음", graph.demoWearableHours == null, { graph.applyDemoWearable(null) })
            listOf(7.5 to "7시간 30분", 5.5 to "5시간 30분", 4.0 to "4시간").forEach { (h, label) ->
                Chip(label, graph.demoWearableHours == h, { graph.applyDemoWearable(h) })
            }
        }
        Group("출발 전 정보 (다음 운행부터)") {
            Chip("캘린더 장거리 일정", graph.demoCalendar, { graph.applyDemoCalendar(!graph.demoCalendar) })
        }
        Text(
            "워치 기록이 있으면 수면 질문을 건너뛰고 출발할 때 실내를 맞춰 둬요(7시간 30분 평소 · 5시간 30분 22도 · 4시간 외기+21도+첫 휴식 1시간). " +
                "시연용 값이에요(오전 6시 기상 / 3시간 출장). 실제 헬스 앱·캘린더 연동은 확장 단계예요.",
            style = ShimpyoType.Small,
            color = Pleos.colors.textTertiary,
            modifier = Modifier.padding(top = 12.dp),
        )
        graph.simCabin?.let { CabinRows(it) }
        Group("가상 주행 (미니맵·차량 특화 놀이)") {
            Chip("쉼터 쪽으로 주행", graph.simDriving, { graph.simulateDrivingTowardFirstShelter() })
            Chip(
                if (graph.usesPleosNavi) "실제 위치" else "멈춤",
                graph.usesPleosNavi && !graph.simDriving,
                { graph.stopSimDrive() },
            )
            Chip("경유지 도착", false, { graph.simulateArrival() })
        }
        Text(
            "운행 중에 차가 첫 졸음쉼터 쪽으로 조금씩 움직여요. 경유지를 넣으면 그쪽으로 가서 도착을 알려요." +
                if (graph.usesPleosNavi) " Pleos 에뮬레이터 위치 대신 쓰고, 경유지는 실제 Pleos 내비에 들어가요." else "",
            style = ShimpyoType.Small,
            color = c.textTertiary,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (ui.shelterCount == 0) {
            Spacer(Modifier.height(16.dp))
            Text(
                "쉼터 데이터가 없어서 내비 검색 화면으로 대신 안내해요. assets에 shelters.csv를 넣으면 가장 가까운 쉼터를 경유지로 바로 넣어요.",
                style = ShimpyoType.Small,
                color = c.textTertiary,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 실제 작동판의 설정: 시연 조작 없이 운전자가 정하는 값만 둔다. */
@Composable
private fun LiveSettings(graph: AppGraph, ui: UiState, modifier: Modifier = Modifier) {
    val c = Pleos.colors
    Column(modifier.verticalScroll(rememberScrollState())) {
        Group("동승자와 같이하기 기본값 (주차 중에만 변경)") {
            TeamPreference.entries.forEach { p ->
                Chip(p.label, ui.teamPreference == p, { graph.applyTeamPreference(p) })
            }
        }
        Text(
            if (ui.parked == true) "동승석에 누가 타면 이 값으로 말놀이를 시작해요."
            else "주차(P단) 중에만 바꿀 수 있어요. 주행 중에는 “같이 할게”, “혼자 할게”라고 말하면 바로 바뀌어요.",
            style = ShimpyoType.Small,
            color = c.textTertiary,
            modifier = Modifier.padding(top = 12.dp),
        )
        Spacer(Modifier.height(36.dp))
        Text(
            "쉼표는 차량 신호(기어·주차 브레이크·착좌 센서)와 내비 위치, 운전자의 말로만 움직여요. " +
                "대답은 말로 하면 되고, 기록 탭에서 쉼표가 한 일을, SDK 진단 탭에서 연결된 차량 기능을 볼 수 있어요.",
            style = ShimpyoType.Small,
            color = c.textTertiary,
        )
        if (ui.shelterCount == 0) {
            Spacer(Modifier.height(16.dp))
            Text("쉼터 데이터가 없어서 내비 검색 화면으로 대신 안내해요.", style = ShimpyoType.Small, color = c.textTertiary)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    val c = Pleos.colors
    Spacer(Modifier.height(36.dp))
    Text(title, style = ShimpyoType.Small, color = c.textTertiary)
    Spacer(Modifier.height(16.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}

@Composable
private fun ListRow(label: String, trailingText: String? = null, trailing: @Composable () -> Unit = {}) {
    val c = Pleos.colors
    Column {
        Row(Modifier.fillMaxWidth().height(88.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = ShimpyoType.Body, color = c.textPrimary, modifier = Modifier.weight(1f))
            if (trailingText != null) {
                Text(trailingText, style = ShimpyoType.UnitLabel, color = c.textTertiary)
                Spacer(Modifier.width(24.dp))
            }
            trailing()
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(c.dimSecondary))
    }
}

@Composable
private fun CabinRows(cabin: SimVehicleGateway.Cabin) {
    val c = Pleos.colors
    Spacer(Modifier.height(20.dp))
    listOf(
        "외기·송풍" to cabin.freshAirBoost,
        (cabin.prepCelsius?.let { "출발 실내 온도 ${it.toInt()}도" } ?: "출발 실내 온도") to (cabin.prepCelsius != null),
        (cabin.restCelsius?.let { "휴식 공조 ${it.toInt()}도" } ?: "휴식 공조") to cabin.restClimate,
        "운전석 등받이" to cabin.seatReclined,
        (cabin.wakeLight?.let { "실내 조명 깨우는 빛 ${(it * 100).toInt()}%" } ?: "실내 조명 낮춤") to cabin.cabinDim,
        "운전석 창문" to cabin.windowOpen,
    ).forEach { (label, on) ->
        ListRow(label) {
            Text(if (on) "켜짐" else "꺼짐", style = ShimpyoType.UnitLabel, color = if (on) c.textPrimary else c.textTertiary)
        }
    }
}

@Composable
private fun LogList(ui: UiState, modifier: Modifier = Modifier) {
    val c = Pleos.colors
    if (ui.logs.isEmpty()) {
        Text("운행을 시작하면 쉼표가 한 일을 여기에 차례로 적어요.", style = ShimpyoType.UnitLabel, color = c.textTertiary, modifier = modifier.padding(top = 24.dp))
        return
    }
    val state = rememberLazyListState()
    LaunchedEffect(ui.logs.size, ui.logs.lastOrNull()?.atMs) { state.scrollToItem(ui.logs.size - 1) }
    val format = remember { SimpleDateFormat("HH:mm:ss", Locale.KOREA) }
    val bootOffset = remember { System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime() }
    LazyColumn(modifier.padding(top = 16.dp), state = state, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        itemsIndexed(ui.logs) { _, line ->
            Row {
                Text(format.format(Date(line.atMs + bootOffset)), style = ShimpyoType.Small, color = c.textTertiary, modifier = Modifier.width(120.dp).padding(top = 3.dp))
                Text(line.text, style = ShimpyoType.UnitLabel, color = c.textSecondary, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DiagList(graph: AppGraph, modifier: Modifier = Modifier) {
    val c = Pleos.colors
    val rows = remember(graph.uiState.shelterCount, graph.uiState.speechBackend) { graph.diagnostics() }
    LazyColumn(modifier.padding(top = 8.dp)) {
        itemsIndexed(rows) { i, row ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(2.dp).background(c.dimPrimary))
            Text(row, style = ShimpyoType.Caption, color = c.textSecondary, modifier = Modifier.padding(vertical = 16.dp))
        }
    }
}

// ── 문구와 의미 색 ─────────────────────────────────────────────────────────

/**
 * 위험 단계 색. 밝은 배경에서 Pleos 노랑(#FFC224)·주황(#FF8A00)은 대비가 1.5:1·2.2:1이라 6dp 계기 호가 잘 안 보인다.
 * 라이트 테마에서만 같은 계열의 진한 색(3.5:1·4.0:1, 그래픽 요소 기준 3:1 이상)으로 바꾼다.
 */
private fun levelColor(level: RiskLevel, c: PleosColors): Color = when (level) {
    RiskLevel.NORMAL -> c.textPrimary
    RiskLevel.CAUTION -> if (c.isDark) c.regulationYellow else Color(0xFFB07A00)
    RiskLevel.WARNING -> if (c.isDark) c.regulationOrange else Color(0xFFC25E00)
    RiskLevel.REST_ADVISED -> c.regulationRed
}

/** 쉼표가 한 말의 글자 크기. 짧으면 32/48, 길어질수록 28/40 → 26/36으로 줄여 5줄 안에 담는다. */
private fun leadStyleFor(text: String) = when {
    text.length <= 72 -> ShimpyoType.Lead
    text.length <= 104 -> ShimpyoType.Lead.copy(fontSize = 28.sp, lineHeight = 40.sp)
    else -> ShimpyoType.Lead.copy(fontSize = 26.sp, lineHeight = 36.sp)
}

private fun headline(ui: UiState): String = when (ui.phase) {
    Phase.IDLE -> "출발할 준비가 됐어요"
    Phase.MONITORING -> when {
        ui.playMode == PlayMode.TEAM -> "동승자와 같이 가는 중"
        ui.quietMode -> "동승자와 함께, 조용히"
        else -> "조용히 지켜보는 중"
    }
    Phase.TALKING -> "대화로 깨우는 중"
    Phase.OFFERING -> "잠깐 쉬어 갈까요"
    Phase.EN_ROUTE -> "쉼터로 안내하는 중"
    Phase.WAITING_PARK -> "주차하면 쉬어요"
    Phase.RESTING -> if (ui.wakeStep != null) "빛으로 깨우는 중" else "낮잠 캡슐"
    Phase.WAKING -> "다시 출발할 준비"
}

private fun guidance(ui: UiState): String = when (ui.phase) {
    Phase.IDLE -> "운행을 시작하면 운전 시간과 대답을 조용히 살필게요."
    Phase.MONITORING ->
        if (ui.quietMode) "대화를 방해하지 않도록 말을 걸지 않아요. 운전 시간과 시간대만 살피다가 필요할 때 한 번만 쉬어 가자고 할게요."
        else "운전 시간과 대답 속도를 함께 보고 있어요."
    Phase.TALKING -> "짧은 대화로 정신을 깨우고 있어요."
    Phase.OFFERING -> "가까운 쉼터에 들를지 묻고 있어요."
    Phase.EN_ROUTE -> "쉼터 안내를 내비에 요청했어요. 안전한 곳에 주차해 주세요."
    Phase.WAITING_PARK -> "P단에 두면 휴식을 시작해요."
    Phase.RESTING -> when (val step = ui.wakeStep) {
        null -> "커피 한 잔 뒤 바로 눈을 붙이세요. 끝나기 3분 전부터 소리 없이 빛과 온도로 서서히 깨워 드릴게요."
        else -> "무드등을 ${step.label}(${(step.light * 100).toInt()}%)으로 밝히는 중이에요." +
            if (step.ordinal >= NapCapsule.WakeStep.MORNING.ordinal) " 실내도 조금 시원하게 했어요." else ""
    }
    Phase.WAKING -> "다시 출발할 준비를 하는 중이에요."
}
