package com.veeha.fastfin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.text.HtmlCompat
import com.veeha.fastfin.ui.theme.FF

@Composable
fun CenterSpinner(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), color = FF.Text, strokeWidth = 2.5.dp)
    }
}

/** iOS large-title header, scrolling with the content. */
@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier.padding(top = 8.dp, bottom = 6.dp), color = FF.Text, fontSize = 34.sp,
        fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp,
    )
}

/** Header for pushed screens: a round back button and the title. */
@Composable
fun TopBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(FF.Background).statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton(Lucide.ChevronLeft, "Back", onBack, diameter = 40.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            title, color = FF.Text, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.4).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun ErrorCard(message: String, onRetry: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Panel(Modifier.widthIn(max = 420.dp), shape = FF.ShapeXl) {
            Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(message, color = FF.Danger, textAlign = TextAlign.Center, fontSize = 14.sp)
                if (onRetry != null) {
                    Spacer(Modifier.height(16.dp))
                    PillButton("Try Again", onRetry, prominent = true, height = 40.dp)
                }
            }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(top = 90.dp, start = 40.dp, end = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, Modifier.size(30.dp), tint = FF.TextDim)
        Text(title, color = FF.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        Text(text, color = FF.TextDim, fontSize = 13.5.sp, textAlign = TextAlign.Center, lineHeight = 19.sp)
    }
}

/** Panel search/filter capsule. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onSearch: () -> Unit = {},
) {
    Panel(modifier.fillMaxWidth().height(46.dp), shape = FF.Pill) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.Search, null, Modifier.size(17.dp), tint = FF.TextDim)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) Text(placeholder, color = FF.TextDim, fontSize = 15.sp, maxLines = 1)
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(color = FF.Text, fontSize = 15.sp),
                    cursorBrush = SolidColor(FF.Text),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Search,
                    ),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier.fillMaxWidth().let { if (focusRequester != null) it.focusRequester(focusRequester) else it },
                )
            }
            if (value.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    Lucide.Close, "Clear", Modifier.size(18.dp).pressable(pressedScale = 0.9f) { onValueChange("") },
                    tint = FF.TextDim,
                )
            }
        }
    }
}

/** Jellyfin overviews are sometimes HTML from the metadata provider
 * ("&mdash;", "<br>"). Shown as plain text with its line breaks; parsed once
 * per string, and skipped entirely for the usual plain overview. */
@Composable
fun rememberPlainText(html: String): String = remember(html) {
    if ('<' !in html && '&' !in html) html
    else HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
}
