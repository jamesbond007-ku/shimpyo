package com.pitstop.shimpyo.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.pitstop.shimpyo.ui.theme.LocalReduceMotion
import com.pitstop.shimpyo.ui.theme.smoothSpec
import com.pitstop.shimpyo.ui.theme.snappySpec

/**
 * 누르는 동안 96%로 살짝 눌렸다가 손을 떼면 스프링으로 돌아온다.
 * 리플 대신 쓰는 반응이라, 버튼 면 자체가 손가락 아래에서 반응하는 느낌을 준다.
 */
@Composable
fun Modifier.pressScale(source: InteractionSource, pressed: Float = 0.96f): Modifier {
    val isPressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) pressed else 1f, snappySpec(), label = "press")
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * 내용이 바뀔 때 이전 것은 흐려지며 위로 빠지고, 새것은 흐릿한 상태에서 선명해지며 올라온다.
 * (iOS의 blur replace 전환과 같은 문법) 제목·문장·버튼 묶음 교체에 쓴다.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun <T> BlurSwap(
    targetState: T,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable (T) -> Unit,
) {
    val reduce = LocalReduceMotion.current
    val fade = smoothSpec<Float>()
    val move = smoothSpec<androidx.compose.ui.unit.IntOffset>()
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        contentAlignment = contentAlignment,
        transitionSpec = {
            (fadeIn(fade) + slideInVertically(move) { it / 5 } + scaleIn(fade, initialScale = 0.98f)) togetherWith
                (fadeOut(fade) + slideOutVertically(move) { -it / 5 }) using SizeTransform(clip = false)
        },
        label = "blurSwap",
    ) { value ->
        val blur by transition.animateDp(label = "blur") { state ->
            if (state == EnterExitState.Visible || reduce) 0.dp else 14.dp
        }
        Box(Modifier.blur(blur, BlurredEdgeTreatment.Unbounded)) { content(value) }
    }
}

/**
 * 숫자가 자리마다 굴러가며 바뀐다. 커지면 아래에서 올라오고, 작아지면 위에서 내려온다.
 * 바뀌지 않은 자리는 가만히 있어서 휴식 카운트다운도 초 단위 자리만 넘어간다.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun RollingNumber(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    // 이전 값과 비교해 굴러갈 방향을 정한다. 화면 갱신을 일으키지 않도록 배열에 보관.
    val memory = remember { arrayOf(text, "up") }
    if (memory[0] != text) {
        val before = memory[0].filter { it.isDigit() }.toLongOrNull()
        val after = text.filter { it.isDigit() }.toLongOrNull()
        memory[1] = if (before != null && after != null && after < before) "down" else "up"
        memory[0] = text
    }
    val up = memory[1] == "up"
    val fade = smoothSpec<Float>()
    val move = smoothSpec<androidx.compose.ui.unit.IntOffset>()
    val size = smoothSpec<androidx.compose.ui.unit.IntSize>()

    Row(modifier.animateContentSize(size), verticalAlignment = Alignment.Bottom) {
        // 오른쪽 끝 자리부터 같은 키를 주어 자릿수가 늘어도 일의 자리가 그대로 유지되게 한다.
        val chars = text.toList()
        chars.forEachIndexed { index, ch ->
            val keyFromEnd = chars.size - index
            androidx.compose.runtime.key(keyFromEnd) {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = {
                        val dir = if (up) 1 else -1
                        (fadeIn(fade) + slideInVertically(move) { h -> h * dir / 2 }) togetherWith
                            (fadeOut(fade) + slideOutVertically(move) { h -> -h * dir / 2 }) using SizeTransform(clip = false)
                    },
                    label = "digit",
                ) { c ->
                    val blur by transition.animateDp(label = "digitBlur") { state ->
                        if (state == EnterExitState.Visible || reduce) 0.dp else 8.dp
                    }
                    Text(c.toString(), style = style, color = color, modifier = Modifier.blur(blur, BlurredEdgeTreatment.Unbounded))
                }
            }
        }
    }
}
