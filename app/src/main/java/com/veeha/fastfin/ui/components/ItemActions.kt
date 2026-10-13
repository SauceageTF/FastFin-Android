package com.veeha.fastfin.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veeha.fastfin.data.Item
import com.veeha.fastfin.data.isWatched
import com.veeha.fastfin.data.withOverride
import com.veeha.fastfin.ui.LocalUserActions
import com.veeha.fastfin.ui.theme.FF

/** The item with the user's latest watched/favourite taps applied, so every card agrees at once. */
@Composable
fun rememberLiveItem(item: Item): Item {
    val actions = LocalUserActions.current ?: return item
    val overrides by actions.overrides.collectAsState()
    val override = overrides[item.id]
    return remember(item, override) { item.withOverride(override) }
}

val Item.isFavorite: Boolean get() = userData?.isFavorite == true

/** Long-press menu for any card: watched and favourite. */
@Composable
fun ItemActionsMenu(item: Item, open: Boolean, onDismiss: () -> Unit) {
    val actions = LocalUserActions.current ?: return
    DropdownMenu(
        expanded = open,
        onDismissRequest = onDismiss,
        shape = FF.ShapeLg,
        containerColor = FF.Panel,
        border = BorderStroke(1.dp, FF.Rim),
        modifier = Modifier.widthIn(min = 220.dp),
    ) {
        Text(
            item.seriesName?.let { "$it · ${item.name}" } ?: item.name,
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp).widthIn(max = 260.dp),
            color = FF.TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        val watched = item.isWatched
        ActionItem(if (watched) "Mark as unwatched" else "Mark as watched", if (watched) Lucide.RotateCcw else Lucide.CheckCircle) {
            actions.setPlayed(item, !watched)
            onDismiss()
        }
        val favorite = item.isFavorite
        ActionItem(if (favorite) "Remove from favourites" else "Add to favourites", if (favorite) Lucide.HeartFilled else Lucide.Heart) {
            actions.setFavorite(item, !favorite)
            onDismiss()
        }
    }
}

@Composable
private fun ActionItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = FF.Text, fontSize = 14.sp) },
        leadingIcon = { Icon(icon, null, Modifier.size(17.dp), tint = FF.TextSecondary) },
        onClick = onClick,
    )
}
