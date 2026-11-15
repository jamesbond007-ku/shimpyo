package com.pitstop.shimpyo.ui.theme

import android.graphics.Paint
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pitstop.shimpyo.R

/**
 * Asta Sans 가변 폰트(SIL OFL 1.1). Pleos 가이드의 기본 서체.
 * 큰 숫자·제목은 300(Light)으로 가늘게, 본문은 400, 버튼은 700만 쓴다.
 */
@OptIn(ExperimentalTextApi::class)
val AstaSans = FontFamily(
    Font(R.font.asta_sans, FontWeight.Light, variationSettings = FontVariation.Settings(FontVariation.weight(300))),
    Font(R.font.asta_sans, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.asta_sans, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.asta_sans, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
)

private fun style(size: Int, line: Int, weight: FontWeight) = TextStyle(
    fontFamily = AstaSans,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = 0.sp,
)

/** Pleos Connect-S 타이포그래피 스케일(크기/행높이 sp). */
@Immutable
object PleosType {
    val Feature = style(112, 134, FontWeight.ExtraBold)
    val HeadlineM = style(56, 72, FontWeight.ExtraBold)
    val HeadlineS = style(48, 64, FontWeight.ExtraBold)
    val TitleL = style(40, 52, FontWeight.Bold)
    val TitleM = style(36, 44, FontWeight.Bold)
    val TitleS = style(32, 40, FontWeight.Bold)
    val BodyLStrong = style(30, 38, FontWeight.Bold)
    val BodyL = style(30, 38, FontWeight.Normal)
    val BodyMStrong = style(28, 36, FontWeight.Bold)
    val BodyM = style(28, 36, FontWeight.Normal)
    val BodySStrong = style(26, 34, FontWeight.Bold)
    val BodyS = style(26, 34, FontWeight.Normal)
    val LabelMStrong = style(24, 28, FontWeight.Bold)
    val LabelM = style(24, 28, FontWeight.Normal)
    val LabelS = style(20, 24, FontWeight.Normal)
}

/**
 * 쉼표 화면 전용 표시 스케일. 크기·행높이는 Pleos 스케일(Title 32/40, Body 30/38·26/34, Label 24/28 등)에
 * 맞추고, 큰 숫자와 제목만 굵기를 낮춰 계기판 같은 정제된 인상을 준다.
 */
@Immutable
object ShimpyoType {
    private fun s(size: Int, line: Int, weight: FontWeight, tracking: Float = 0f) = TextStyle(
        fontFamily = AstaSans,
        fontSize = size.sp,
        lineHeight = line.sp,
        fontWeight = weight,
        letterSpacing = (size * tracking).sp,
        fontFeatureSettings = "tnum",
        lineBreak = KoreanLineBreak,
    )

    /**
     * 한국어를 글자가 아니라 띄어쓰기(어절) 단위로 줄바꿈한다(“오창 졸 / 음쉼터” 대신 “오창 / 졸음쉼터”).
     * Android 13(API 33)부터 동작하고 Pleos Connect는 API 34다. 그보다 낮으면 기본 줄바꿈으로 돌아간다.
     */
    private val KoreanLineBreak = LineBreak(
        strategy = LineBreak.Strategy.HighQuality,
        strictness = LineBreak.Strictness.Normal,
        wordBreak = LineBreak.WordBreak.Phrase,
    )
    val Dial = s(168, 168, FontWeight.Light, -0.02f)
    val Phase = s(64, 80, FontWeight.Light, -0.01f)
    val Lead = s(32, 48, FontWeight.Normal)
    val Fact = s(44, 52, FontWeight.Light)
    val UnitLabel = s(26, 34, FontWeight.Normal)
    val Body = s(28, 36, FontWeight.Normal)
    val Caption = s(24, 28, FontWeight.Normal)
    val Small = s(22, 28, FontWeight.Normal)
    val Button = s(30, 38, FontWeight.Normal)
    val ButtonStrong = s(30, 38, FontWeight.Bold)
    val Word = s(32, 40, FontWeight.Normal, 0.02f)
}

/** Pleos 그림자 L1~L3. (y, blur, alpha) 레이어. Figma blur는 시그마의 2배라 그릴 때 절반으로 쓴다. */
enum class PleosShadow(val layers: List<Triple<Dp, Dp, Float>>) {
    L1(listOf(Triple(6.dp, 24.dp, 0.08f))),
    L2(listOf(Triple(8.dp, 32.dp, 0.16f), Triple(2.dp, 2.dp, 0.08f))),
    L3(listOf(Triple(20.dp, 40.dp, 0.24f), Triple(2.dp, 4.dp, 0.20f))),
}

/**
 * 배경과 Pleos 그림자를 함께 그린다. Compose의 elevation은 Material 그림자 모델이라
 * 가이드의 수치(오프셋·블러·알파)를 그대로 낼 수 없어서 setShadowLayer로 직접 그린다(API 28+ 하드웨어 가속 지원).
 */
fun Modifier.pleosSurface(color: Color, radius: Dp, shadow: PleosShadow?, dark: Boolean): Modifier = drawBehind {
    val r = radius.toPx()
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        if (shadow != null) {
            // 다크 모드에서는 그림자가 거의 보이지 않으므로 가이드처럼 면 대비로 위계를 표현한다.
            val strength = if (dark) 1.6f else 1f
            shadow.layers.forEach { (y, blur, alpha) ->
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color.toArgb()
                    setShadowLayer(blur.toPx() / 2f, 0f, y.toPx(), Color.Black.copy(alpha = (alpha * strength).coerceAtMost(1f)).toArgb())
                }
                native.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint)
            }
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toArgb() }
        native.drawRoundRect(0f, 0f, size.width, size.height, r, r, fill)
    }
}

val LocalPleosColors = staticCompositionLocalOf { PleosLight }

object Pleos {
    val colors: PleosColors
        @Composable get() = LocalPleosColors.current
}

/** 호스트(차량)의 라이트/다크 설정을 그대로 따른다. AAOS의 야간 모드도 같은 경로로 들어온다. */
@Composable
fun ShimpyoTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) PleosDark else PleosLight
    // 남아 있는 Material 요소(스크롤 효과 등)도 같은 팔레트를 쓰도록 맞춘다.
    val scheme = if (dark) {
        darkColorScheme(primary = c.positive, background = c.backgroundPrimary, surface = c.surfaceBasic, onSurface = c.textPrimary)
    } else {
        lightColorScheme(primary = c.positive, background = c.backgroundPrimary, surface = c.surfaceBasic, onSurface = c.textPrimary)
    }
    CompositionLocalProvider(LocalPleosColors provides c, LocalReduceMotion provides rememberReduceMotion()) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
