package com.veeha.fastfin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veeha.fastfin.ui.LocalGraph
import com.veeha.fastfin.ui.components.Panel
import com.veeha.fastfin.ui.components.Lucide
import com.veeha.fastfin.ui.components.pressable
import com.veeha.fastfin.ui.theme.FF
import com.veeha.fastfin.ui.theme.LocalAccent
import com.veeha.fastfin.ui.theme.color
import com.veeha.fastfin.ui.theme.hover
import kotlinx.coroutines.launch

private val Bloom = Color(0xFF5A1E0A)

@Composable
fun LoginScreen() {
    val graph = LocalGraph.current
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()
    var server by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var signingIn by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    val canSubmit = server.isNotBlank() && username.isNotBlank() && !signingIn
    fun submit() {
        // Re-read the fields at tap time. A value captured during an earlier
        // composition (empty fields) is exactly what made the button ignore taps.
        if (signingIn) return
        // Say what is missing instead of silently ignoring the tap.
        when {
            server.isBlank() -> { error = "Enter your server address."; return }
            username.isBlank() -> { error = "Enter your username."; return }
        }
        signingIn = true
        error = null
        scope.launch {
            try {
                error = graph.sessions.signIn(graph.http, server, username, password)
            } finally {
                // Whatever happened, the button must come back.
                signingIn = false
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(FF.Background)
            .drawBehind {
                // Soft ember bloom and two orbs behind the sign-in card.
                drawRect(Brush.linearGradient(listOf(Bloom, FF.Background, FF.Background), Offset(size.width, 0f), Offset(size.width * 0.2f, size.height * 0.9f)))
                val a = Offset(size.width + 90.dp.toPx() - 160.dp.toPx(), 80.dp.toPx())
                drawCircle(Brush.radialGradient(listOf(Color(0x47FF5A1F), Color.Transparent), a, 160.dp.toPx()), 160.dp.toPx(), a)
                val b = Offset(20.dp.toPx(), size.height - 190.dp.toPx())
                drawCircle(Brush.radialGradient(listOf(Color(0x262DD4C8), Color.Transparent), b, 130.dp.toPx()), 130.dp.toPx(), b)
            }
    ) {
        // safeDrawing already includes the keyboard, so the card rises with it
        // exactly once. heightIn(min) keeps the card centred while still
        // letting it scroll when the keyboard leaves too little room.
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val viewport = maxHeight
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = viewport).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Panel(Modifier.fillMaxWidth().widthIn(max = 380.dp), shape = FF.ShapeXl) {
                Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .shadow(18.dp, RoundedCornerShape(18.dp), ambientColor = accent.color, spotColor = accent.color)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Brush.verticalGradient(listOf(accent.hover, accent.color))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Lucide.Play, null, Modifier.size(22.dp), tint = FF.Text)
                    }
                    Text("FastFin", Modifier.padding(top = 16.dp), color = FF.Text, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
                    Text(
                        "Sign in to your Jellyfin server", Modifier.padding(top = 6.dp, bottom = 26.dp),
                        color = FF.TextDim, fontSize = 13.5.sp, textAlign = TextAlign.Center,
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Field("Server", server, { server = it }, "e.g. 192.168.1.20:8096", KeyboardType.Uri, ImeAction.Next, null)
                        Field("Username", username, { username = it }, "Username", KeyboardType.Text, ImeAction.Next, ContentType.Username)
                        Field(
                            "Password", password, { password = it }, "Password", KeyboardType.Password, ImeAction.Go,
                            ContentType.Password, secret = true, onGo = { submit() },
                        )
                    }

                    error?.let {
                        Text(it, Modifier.padding(top = 14.dp), color = FF.Danger, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    }

                    Box(
                        Modifier
                            .padding(top = 20.dp)
                            .fillMaxWidth()
                            .height(50.dp)
                            .pressable(enabled = !signingIn, pressedScale = 0.98f, haptic = true) { submit() }
                            .clip(FF.Pill)
                            .background(if (canSubmit) FF.Text else FF.Text.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (signingIn) CircularProgressIndicator(Modifier.size(22.dp), color = FF.OnLight, strokeWidth = 2.dp)
                        else Text("Sign In", color = FF.OnLight, fontSize = 15.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboard: KeyboardType,
    ime: ImeAction,
    autofill: ContentType?,
    secret: Boolean = false,
    onGo: () -> Unit = {},
) {
    val shape = RoundedCornerShape(FF.RadiusMd)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label.uppercase(), Modifier.padding(horizontal = 2.dp), color = FF.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = FF.Text, fontSize = 15.sp),
            cursorBrush = SolidColor(FF.Text),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = keyboard,
                imeAction = ime,
            ),
            keyboardActions = KeyboardActions(onGo = { onGo() }),
            modifier = Modifier
                .fillMaxWidth()
                .let { if (autofill != null) it.semantics { contentType = autofill } else it },
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clip(shape)
                        .background(FF.Field)
                        .border(Dp.Hairline, FF.Rim, shape)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) Text(placeholder, color = Color(0x59FFFFFF), fontSize = 15.sp)
                    inner()
                }
            },
        )
    }
}
