package com.pitstop.shimpyo.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/*
 * 모션 원칙
 *  - 시간이 아니라 스프링으로 움직인다. 손을 떼는 순간의 속도를 이어받아 멈추므로 끊김이 없다.
 *    (SwiftUI처럼 response·damping으로 잡고 stiffness = (2π/response)²로 바꿨다. 기본은 response 0.5s)
 *  - 움직임은 상태가 바뀔 때, 사용자가 손댈 때만 생긴다. 주행 중 반복되는 장식 모션은 없다(Pleos 원칙).
 *    유일한 반복 모션은 주차 후 휴식 중의 호흡 안내다.
 *  - 시스템 '애니메이션 삭제'(애니메이터 배율 0)가 켜져 있으면 모든 전환을 즉시 바꾼다.
 */
object Motion {
    /** 화면 요소의 기본 전환. 출렁임 없이 부드럽게 멈춘다. (response ≈ 0.5s) */
    const val SMOOTH_STIFFNESS = 158f
    const val SMOOTH_DAMPING = 0.86f

    /** 누름·토글처럼 손에 바로 붙어야 하는 반응. (response ≈ 0.32s) */
    const val SNAPPY_STIFFNESS = 385f
    const val SNAPPY_DAMPING = 0.80f

    /** 시트처럼 큰 면의 이동. 끝에서 아주 살짝 넘쳤다 돌아온다. (response ≈ 0.42s) */
    const val SHEET_STIFFNESS = 224f
    const val SHEET_DAMPING = 0.84f

    /** 첫 등장처럼 한 번뿐인 연출에 쓰는 감속 곡선. */
    val EaseOutExpressive = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
}

val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

@Composable
@ReadOnlyComposable
fun <T> smoothSpec(): FiniteAnimationSpec<T> =
    if (LocalReduceMotion.current) snap() else spring(Motion.SMOOTH_DAMPING, Motion.SMOOTH_STIFFNESS)

@Composable
@ReadOnlyComposable
fun <T> snappySpec(): FiniteAnimationSpec<T> =
    if (LocalReduceMotion.current) snap() else spring(Motion.SNAPPY_DAMPING, Motion.SNAPPY_STIFFNESS)

@Composable
@ReadOnlyComposable
fun <T> sheetSpec(): FiniteAnimationSpec<T> =
    if (LocalReduceMotion.current) snap() else spring(Motion.SHEET_DAMPING, Motion.SHEET_STIFFNESS)

@Composable
@ReadOnlyComposable
fun <T> introSpec(durationMs: Int, delayMs: Int = 0): FiniteAnimationSpec<T> =
    if (LocalReduceMotion.current) snap() else tween(durationMs, delayMs, Motion.EaseOutExpressive)
