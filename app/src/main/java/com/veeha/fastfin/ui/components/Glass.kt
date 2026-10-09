package com.veeha.fastfin.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veeha.fastfin.ui.theme.FF

/** `Regular` is the frosted default; `Clear` is the lighter variant Apple
 * uses over media (the player HUD). */
enum class GlassStyle { Regular, Clear }

private val RegularFill = Color(0xC81D1D24)
private val ClearFill = Color(0x3C0E0E12)
/** Specular sheen along the top edge, what iOS draws on real Liquid Glass. */
private val Sheen = Brush.verticalGradient(listOf(Color(0x17FFFFFF), Color.Transparent))

/**
 * The one surface every piece of chrome is built on, matching the iOS
 * build's blur fallback: a translucent tint, a top sheen, a hairline rim.
 *
 * Deliberately no live backdrop blur. Blurring whatever scrolls underneath
 * means re-rendering it every frame, and a SurfaceView video cannot be
 * sampled at all. Flat translucency costs nothing.
 */
fun Modifier.glass(shape: Shape, style: GlassStyle = GlassStyle.Regular, tint: Color? = null): Modifier =
    this
        .clip(shape)
        .background(tint ?: if (style == GlassStyle.Regular) RegularFill else ClearFill)
        .background(Sheen)
        .border(Dp.Hairline, FF.GlassRim, shape)

@Composable
fun Glass(
    modifier: Modifier = Modifier,
    shape: Shape = FF.ShapeLg,
    style: GlassStyle = GlassStyle.Regular,
    tint: Color? = null,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier.glass(shape, style, tint), contentAlignment = contentAlignment, content = content)
}

/**
 * iOS-style press feedback: a quick spring scale instead of a ripple. The
 * scale is read in the graphics layer, so pressing never recomposes the
 * content.
 */
fun Modifier.pressable(
    enabled: Boolean = true,
    pressedScale: Float = 0.97f,
    role: Role = Role.Button,
    haptic: Boolean = false,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, spring(dampingRatio = 0.7f, stiffness = 900f), label = "press")
    val view = LocalView.current
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = role) {
            if (haptic) view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            onClick()
        }
}

/** Circular glass icon button: back, info, search, player chrome. */
@Composable
fun GlassButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 44.dp,
    style: GlassStyle = GlassStyle.Regular,
    iconSize: Dp = diameter * 0.42f,
    iconTint: Color = FF.Text,
    enabled: Boolean = true,
    content: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier
            .size(diameter)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .pressable(enabled = enabled, pressedScale = 0.94f, haptic = true, onClick = onClick)
            .glass(CircleShape, style)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        if (content != null) content() else Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize), tint = iconTint)
    }
}

/** Capsule button. `prominent` is the white Play button; otherwise glass. */
@Composable
fun GlassPillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    prominent: Boolean = false,
    height: Dp = 46.dp,
) {
    val foreground = if (prominent) FF.OnLight else FF.Text
    Row(
        modifier
            .height(height)
            .pressable(haptic = true, onClick = onClick)
            .then(if (prominent) Modifier.clip(FF.Pill).background(FF.Text) else Modifier.glass(FF.Pill))
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp), tint = foreground)
        Text(label, color = foreground, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}
