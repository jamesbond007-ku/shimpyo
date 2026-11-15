package com.pitstop.shimpyo.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pitstop.shimpyo.ui.theme.Pleos
import com.pitstop.shimpyo.ui.theme.ShimpyoType
import com.pitstop.shimpyo.ui.theme.smoothSpec
import com.pitstop.shimpyo.ui.theme.snappySpec

/*
 * 쉼표 화면의 기본 부품. 면·그림자·아이콘 대신 선 굵기와 글자 굵기로 위계를 만든다.
 * 크기는 Pleos 규격을 지킨다: Large 버튼 높이 100, 칩 64, 최소 터치 48dp, 공식 로딩 모션.
 */

enum class PillStyle { Filled, Outline, Quiet }

/** 높이 100dp의 알약형 버튼. 화면마다 Filled는 하나만 둔다. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.Outline,
    enabled: Boolean = true,
    /** 좁은 화면(내비와 나눠 띄운 분할 화면)에서는 좌우 여백과 최소 폭을 줄여 버튼이 잘리지 않게 한다. */
    compact: Boolean = false,
) {
    val c = Pleos.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val ink = c.textPrimary
    val shape = RoundedCornerShape(50.dp)
    val bg = when (style) {
        PillStyle.Filled -> if (enabled) ink else c.dimPrimary
        else -> if (pressed) c.dimPrimary else Color.Transparent
    }
    val fg = when {
        !enabled -> c.textQuaternary
        style == PillStyle.Filled -> c.backgroundSecondary.takeIf { c.isDark } ?: Color.White
        style == PillStyle.Quiet -> c.textTertiary
        else -> c.textPrimary
    }
    val bgAnimated by animateColorAsState(bg, snappySpec(), label = "pillBg")
    val fgAnimated by animateColorAsState(fg, snappySpec(), label = "pillFg")
    Box(
        modifier
            .pressScale(source)
            .defaultMinSize(minWidth = if (compact) 0.dp else 200.dp, minHeight = 100.dp)
            .clip(shape)
            .background(bgAnimated)
            .then(if (style == PillStyle.Outline) Modifier.border(2.dp, if (enabled) c.dimTertiary else c.dimSecondary, shape) else Modifier)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (compact) 16.dp else if (style == PillStyle.Quiet) 40.dp else 56.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = if (style == PillStyle.Filled) ShimpyoType.ButtonStrong else ShimpyoType.Button, color = fgAnimated, maxLines = 1)
    }
}

/** 64dp 칩. 선택되면 잉크색으로 채워지고, 누르는 동안 살짝 눌린다. */
@Composable
fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 28.dp,
) {
    val c = Pleos.colors
    val shape = RoundedCornerShape(32.dp)
    val source = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (selected) c.textPrimary else Color.Transparent, snappySpec(), label = "chipBg")
    val fg by animateColorAsState(
        if (selected) (if (c.isDark) c.backgroundSecondary else Color.White) else c.textPrimary,
        snappySpec(),
        label = "chipFg",
    )
    val border by animateColorAsState(if (selected) Color.Transparent else c.dimTertiary, snappySpec(), label = "chipBorder")
    Box(
        modifier
            .pressScale(source, 0.94f)
            .height(64.dp)
            .clip(shape)
            .background(bg)
            .border(2.dp, border, shape)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = ShimpyoType.Caption, color = fg, maxLines = 1)
    }
}

/** 밑줄 탭. 밑줄이 선택한 탭 아래로 스프링을 타고 미끄러지며 폭도 글자에 맞춰 늘고 준다. */
@Composable
fun <T> LineTabs(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = Pleos.colors
    val density = LocalDensity.current
    val bounds = remember(options.size) { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val index = options.indexOfFirst { it.second == selected }.coerceAtLeast(0)
    val target = bounds[index] ?: (0f to 0f)
    val x by animateFloatAsState(target.first, snappySpec(), label = "tabX")
    val w by animateFloatAsState(target.second, snappySpec(), label = "tabW")
    Column(modifier.fillMaxWidth()) {
        Box {
            Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                options.forEachIndexed { i, (label, value) ->
                    val on = i == index
                    val color by animateColorAsState(if (on) c.textPrimary else c.textTertiary, snappySpec(), label = "tabText")
                    Text(
                        label,
                        style = ShimpyoType.Body,
                        color = color,
                        modifier = Modifier
                            .onGloballyPositioned { bounds[i] = it.positionInParent().x to it.size.width.toFloat() }
                            .clickable(role = Role.Tab, onClick = { onSelect(value) })
                            .padding(top = 12.dp, bottom = 19.dp),
                    )
                }
            }
            if (w > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = with(density) { x.toDp() })
                        .width(with(density) { w.toDp() })
                        .height(3.dp)
                        .background(c.textPrimary),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(c.dimSecondary))
    }
}

/** 무채색 스위치. 누르고 있으면 손잡이가 옆으로 늘어나고, 놓으면 스프링으로 건너간다. */
@Composable
fun MonoSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = Pleos.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val track by animateColorAsState(if (checked) c.textPrimary else c.dimTertiary, smoothSpec(), label = "track")
    val x by animateFloatAsState(if (checked) 1f else 0f, snappySpec(), label = "knob")
    val stretch by animateFloatAsState(if (pressed) 1f else 0f, snappySpec(), label = "stretch")
    val knob = if (checked) (if (c.isDark) c.backgroundSecondary else Color.White) else Color.White
    val knobWidth = 40f + 10f * stretch
    Box(
        modifier
            .size(width = 88.dp, height = 52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(track)
            .toggleable(value = checked, interactionSource = source, indication = null, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(6.dp),
    ) {
        Box(
            Modifier
                .offset(x = ((76f - knobWidth) * x).dp)
                .size(width = knobWidth.dp, height = 40.dp)
                .background(knob, RoundedCornerShape(20.dp)),
        )
    }
}

/**
 * Pleos 공식 Loading 모션: 64 뷰포트, 반지름 24, 선 8, 2초에 1080° 회전하는 28% 호.
 * 크기를 줄일 때는 선 굵기도 같은 비율로 줄인다.
 */
@Composable
fun PleosLoading(size: Dp = 64.dp, color: Color = Pleos.colors.textPrimary, description: String = "처리 중") {
    val t = rememberInfiniteTransition(label = "loading")
    val angle by t.animateFloat(0f, 1080f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "angle")
    Canvas(Modifier.size(size).semantics { contentDescription = description }) {
        val scale = this.size.minDimension / 64f
        val stroke = 8f * scale
        val radius = 24f * scale
        val topLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2, radius * 2)
        drawCircle(color.copy(alpha = 0.10f), radius, center, style = Stroke(stroke))
        rotate(angle) {
            drawArc(color.copy(alpha = 0.84f), -90f, 360f * 0.28f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}
