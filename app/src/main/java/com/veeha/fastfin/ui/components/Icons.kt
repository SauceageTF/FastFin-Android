package com.veeha.fastfin.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The Lucide icons Spotifast ships (ISC licence), as Compose vectors.
 * A hand-picked set instead of material-icons-extended: each icon is a
 * couple of path strings, built once on first use.
 */
object Lucide {
    val Home by lazy {
        stroke(
            "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8",
            "M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
        )
    }
    val Grid by lazy { stroke(rect(3f, 3f, 7f, 7f, 1f), rect(14f, 3f, 7f, 7f, 1f), rect(14f, 14f, 7f, 7f, 1f), rect(3f, 14f, 7f, 7f, 1f)) }
    val Sliders by lazy {
        stroke("M21 4h-7", "M10 4H3", "M21 12h-9", "M8 12H3", "M21 20h-5", "M12 20H3", "M14 2v4", "M8 10v4", "M16 18v4")
    }
    val Search by lazy { stroke(circle(11f, 11f, 8f), "m21 21-4.3-4.3") }
    val ArrowUp by lazy { stroke("m5 12 7-7 7 7", "M12 19V5") }
    val ArrowDown by lazy { stroke("M12 5v14", "m19 12-7 7-7-7") }
    val ArrowUpDown by lazy { stroke("m21 16-4 4-4-4", "M17 20V4", "m3 8 4-4 4 4", "M7 4v16") }
    val Filter by lazy { stroke("M3 6h18", "M7 12h10", "M10 18h4") }
    val Play by lazy { filled("M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z") }
    val Pause by lazy { filled(rect(14f, 3f, 5f, 18f, 1f), rect(5f, 3f, 5f, 18f, 1f)) }
    val Info by lazy { stroke(circle(12f, 12f, 10f), "M12 16v-4", "M12 8h.01") }
    val ChevronLeft by lazy { stroke("m15 18-6-6 6-6") }
    val ChevronRight by lazy { stroke("m9 18 6-6-6-6") }
    val ChevronDown by lazy { stroke("m6 9 6 6 6-6") }
    val Close by lazy { stroke("M18 6 6 18", "m6 6 12 12") }
    val Check by lazy { stroke("M20 6 9 17l-5-5") }
    val RotateCcw by lazy { stroke("M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5") }
    val RotateCw by lazy { stroke("M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8", "M21 3v5h-5") }
    val Captions by lazy { stroke(rect(3f, 5f, 18f, 14f, 2f), "M7 15h4M15 15h2M7 11h2M13 11h4") }
    val PictureInPicture by lazy {
        stroke("M21 9V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v10c0 1.1.9 2 2 2h4", rect(12f, 13f, 10f, 7f, 2f))
    }
    val Star by lazy {
        filled(
            "M11.525 2.295a.53.53 0 0 1 .95 0l2.31 4.679a2.123 2.123 0 0 0 1.595 1.16l5.166.756a.53.53 0 0 1 .294.904l-3.736 3.638a2.123 2.123 0 0 0-.611 1.878l.882 5.14a.53.53 0 0 1-.771.56l-4.618-2.428a2.122 2.122 0 0 0-1.973 0L6.396 21.01a.53.53 0 0 1-.77-.56l.881-5.139a2.122 2.122 0 0 0-.611-1.879L2.16 9.795a.53.53 0 0 1 .294-.906l5.165-.755a2.122 2.122 0 0 0 1.597-1.16z",
        )
    }
    val Rows by lazy { stroke("M3 12h.01", "M3 18h.01", "M3 6h.01", "M8 12h13", "M8 18h13", "M8 6h13") }
    val Film by lazy {
        stroke(rect(3f, 3f, 18f, 18f, 2f), "M7 3v18", "M3 7.5h4", "M3 12h18", "M3 16.5h4", "M17 3v18", "M17 7.5h4", "M17 16.5h4")
    }
    val Tv by lazy { stroke("m17 2-5 5-5-5", rect(2f, 7f, 20f, 15f, 2f)) }
    val Music by lazy { stroke("M9 18V5l12-2v13", circle(6f, 18f, 3f), circle(18f, 16f, 3f)) }
    val Video by lazy {
        stroke("m16 13 5.223 3.482a.5.5 0 0 0 .777-.416V7.87a.5.5 0 0 0-.752-.432L16 10.5", rect(2f, 6f, 14f, 12f, 2f))
    }
    val Image by lazy { stroke(rect(3f, 3f, 18f, 18f, 2f), circle(9f, 9f, 2f), "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21") }
    val Book by lazy { stroke("M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H19a1 1 0 0 1 1 1v18a1 1 0 0 1-1 1H6.5a1 1 0 0 1 0-5H20") }
    val Folder by lazy {
        stroke("M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z")
    }
    val Server by lazy { stroke(rect(2f, 2f, 20f, 8f, 2f), rect(2f, 14f, 20f, 8f, 2f), "M6 6h.01", "M6 18h.01") }
    val User by lazy { stroke(circle(12f, 12f, 10f), circle(12f, 10f, 3f), "M7 20.662V19a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v1.662") }
    val Phone by lazy { stroke(rect(5f, 2f, 14f, 20f, 2f), "M12 18h.01") }
    val Sparkles by lazy {
        stroke(
            "M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1.966l-5.558 1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0-1.594-1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594z",
            "M20 2v4", "M22 4h-4", circle(4f, 20f, 2f),
        )
    }
    val LogOut by lazy { stroke("M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4", "m16 17 5-5-5-5", "M21 12H9") }
    val CheckCircle by lazy { stroke(circle(12f, 12f, 10f), "m9 12 2 2 4-4") }
    val PlayCircle by lazy { stroke(circle(12f, 12f, 10f), "M10 8l6 4-6 4z") }
    val Waves by lazy { stroke("M2 10v3", "M6 6v11", "M10 3v18", "M14 8v7", "M18 5v13", "M22 10v3") }
    val Speaker by lazy {
        stroke(
            "M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z",
            "M16 9a5 5 0 0 1 0 6", "M19.364 18.364a9 9 0 0 0 0-12.728",
        )
    }
    val Monitor by lazy { stroke(rect(2f, 3f, 20f, 14f, 2f), "M8 21h8", "M12 17v4") }
    val Cpu by lazy {
        stroke(
            rect(4f, 4f, 16f, 16f, 2f), rect(9f, 9f, 6f, 6f, 1f),
            "M15 2v2M15 20v2M2 15h2M2 9h2M20 15h2M20 9h2M9 2v2M9 20v2",
        )
    }
    val HardDrive by lazy {
        stroke(
            "M22 12H2", "M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z",
            "M6 16h.01", "M10 16h.01",
        )
    }
    val Gauge by lazy { stroke("m12 14 4-4", "M3.34 19a10 10 0 1 1 17.32 0") }
    val Alert by lazy { stroke("m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3", "M12 9v4", "M12 17h.01") }

    private fun build(paths: Array<out String>, filled: Boolean): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            for (d in paths) {
                addPath(
                    pathData = addPathNodes(d),
                    fill = if (filled) SolidColor(Color.Black) else null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    private fun stroke(vararg paths: String) = build(paths, filled = false)
    private fun filled(vararg paths: String) = build(paths, filled = true)

    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

    private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}" +
            "h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"
}
