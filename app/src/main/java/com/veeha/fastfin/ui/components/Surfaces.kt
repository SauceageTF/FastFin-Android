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
import androidx.compose.runtime.rememberUpdatedState
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

/** `Raised` is a solid panel for chrome on the app background; `Overlay`
 * is the dark disc used for controls laid over artwork and video. */
enum class PanelStyle { Raised, Overlay }

/** Light from above: the top of a raised panel is a shade lighter. */
private val PanelFill = Brush.verticalGradient(listOf(FF.PanelTop, FF.Panel))

/**
 * The one surface every piece of chrome is built on. Opaque on purpose:
 * Android has no system glass, and a translucent tint over busy artwork
 * reads as muddy rather than glassy. Raised panels get a soft top-lit
 * gradient and an opaque hairline edge; overlays are a plain dark disc.
 */
fun Modifier.panel(shape: Shape, style: PanelStyle = PanelStyle.Raised, tint: Color? = null): Modifier =
    when {
        tint != null -> this.clip(shape).background(tint)
        style == PanelStyle.Overlay -> this.clip(shape).background(FF.Overlay)
        else -> this.clip(shape).background(PanelFill).border(1.dp, FF.Rim, shape)
    }

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    shape: Shape = FF.ShapeLg,
    style: PanelStyle = PanelStyle.Raised,
    tint: Color? = null,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier.panel(shape, style, tint), contentAlignment = contentAlignment, content = content)
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
    // The click lambda changes as the screen recomposes (a sign-in button's
    // fields fill in). Always call the latest one, never the first captured.
    val currentOnClick by rememberUpdatedState(onClick)
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
            currentOnClick()
        }
}

/** Round icon button: back, info, search, player chrome. */
@Composable
fun RoundButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 44.dp,
    style: PanelStyle = PanelStyle.Raised,
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
            .panel(CircleShape, style)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        if (content != null) content() else Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize), tint = iconTint)
    }
}

/** Capsule button. `prominent` is the white Play button; otherwise a raised panel. */
@Composable
fun PillButton(
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
            .then(if (prominent) Modifier.clip(FF.Pill).background(FF.Text) else Modifier.panel(FF.Pill))
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp), tint = foreground)
        Text(label, color = foreground, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}
