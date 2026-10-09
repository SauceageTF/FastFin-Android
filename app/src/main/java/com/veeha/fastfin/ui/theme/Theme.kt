package com.veeha.fastfin.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.veeha.fastfin.data.AccentName

/** Tokens ported from the iOS build's lib/theme.ts. Dark-first: glass always
 * sits on a dark ground. */
object FF {
    val Background = Color(0xFF0A0A0E)
    val Elevated = Color(0xFF18181C)
    val Hover = Color(0xFF232327)
    val Text = Color(0xFFF2F1F6)
    val TextSecondary = Color(0xFFC9C7D1)
    val TextDim = Color(0xFFA9A7B5)
    val Danger = Color(0xFFFF5C5C)
    val Border = Color(0xFF2C2C30)

    /** Hairline rim on glass surfaces (white 14%). */
    val GlassRim = Color(0x24FFFFFF)
    val GlassFill = Color(0x14FFFFFF)
    val GlassFillStrong = Color(0x29FFFFFF)

    /** Dark text on the white "prominent" buttons. */
    val OnLight = Color(0xFF141018)

    val RadiusSm = 10.dp
    val RadiusMd = 14.dp
    val RadiusLg = 20.dp
    val RadiusXl = 28.dp

    val ShapeMd = RoundedCornerShape(RadiusMd)
    val ShapeLg = RoundedCornerShape(RadiusLg)
    val ShapeXl = RoundedCornerShape(RadiusXl)
    val Pill = RoundedCornerShape(percent = 50)
}

val AccentName.color: Color
    get() = when (this) {
        AccentName.Frost -> Color(0xFFF2F1F6)
        AccentName.Teal -> Color(0xFF2DD4C8)
        AccentName.Ember -> Color(0xFFFF5A1F)
    }

val AccentName.hover: Color
    get() = when (this) {
        AccentName.Frost -> Color(0xFFD8D7DB)
        AccentName.Teal -> Color(0xFF55E0D6)
        AccentName.Ember -> Color(0xFFFF7A3D)
    }

val LocalAccent = staticCompositionLocalOf { AccentName.Ember }

/**
 * Every plain Text inherits bodyLarge. Material's 24 sp line height and
 * 0.5 sp tracking are sized for 16 sp body copy; on the 11–15 sp labels this
 * design uses, they inflate each line by up to half again and push stacked
 * lines out of their pills. Natural line height and no tracking match iOS.
 */
private val BaseTypography = Typography()
private val AppTypography = BaseTypography.copy(
    bodyLarge = BaseTypography.bodyLarge.copy(lineHeight = TextUnit.Unspecified, letterSpacing = 0.sp),
)

@Composable
fun FastFinTheme(accent: AccentName, content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = accent.color,
        onPrimary = FF.OnLight,
        secondary = accent.hover,
        background = FF.Background,
        onBackground = FF.Text,
        surface = FF.Elevated,
        onSurface = FF.Text,
        surfaceVariant = FF.Hover,
        onSurfaceVariant = FF.TextDim,
        surfaceContainer = FF.Hover,
        surfaceContainerHigh = FF.Hover,
        outline = FF.Border,
        outlineVariant = FF.GlassRim,
        error = FF.Danger,
    )
    MaterialTheme(colorScheme = scheme, typography = AppTypography) {
        CompositionLocalProvider(LocalAccent provides accent, LocalContentColor provides FF.Text, content = content)
    }
}
