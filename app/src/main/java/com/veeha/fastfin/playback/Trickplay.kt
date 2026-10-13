package com.veeha.fastfin.playback

import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.veeha.fastfin.data.Item
import com.veeha.fastfin.data.TrickplayInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Where one item's scrubbing thumbnails live, and how they are laid out. */
@Immutable
data class TrickplaySource(val itemId: String, val mediaSourceId: String, val info: TrickplayInfo, val serverUrl: String) {

    /** A thumbnail's place in its sheet image, in pixels. */
    data class Cell(val sheetUrl: String, val x: Int, val y: Int, val width: Int, val height: Int)

    /** The sheet image holding the thumbnail for `positionMs`, and where in it. */
    fun locate(positionMs: Long): Cell {
        val perSheet = (info.tileWidth * info.tileHeight).coerceAtLeast(1)
        val index = (positionMs / info.interval.coerceAtLeast(1)).toInt().coerceIn(0, (info.thumbnailCount - 1).coerceAtLeast(0))
        val sheet = index / perSheet
        val cell = index % perSheet
        val x = (cell % info.tileWidth) * info.width
        val y = (cell / info.tileWidth) * info.height
        val url = "$serverUrl/Videos/$itemId/Trickplay/${info.width}/$sheet.jpg?mediaSourceId=$mediaSourceId"
        return Cell(url, x, y, info.width, info.height)
    }

    val aspect: Float get() = info.width.toFloat() / info.height.coerceAtLeast(1)

    companion object {
        /** Thumbnails around this width look sharp in a ~200 dp preview without heavy sheets. */
        private const val PREFERRED_WIDTH = 320

        fun of(item: Item, mediaSourceId: String?, serverUrl: String): TrickplaySource? {
            val bySource = item.trickplay ?: return null
            val resolutions = (mediaSourceId?.let(bySource::get) ?: bySource.values.firstOrNull()) ?: return null
            val info = resolutions.values
                .filter { it.width > 0 && it.height > 0 && it.tileWidth > 0 && it.tileHeight > 0 && it.thumbnailCount > 0 && it.interval > 0 }
                .minByOrNull { kotlin.math.abs(it.width - PREFERRED_WIDTH) } ?: return null
            val sourceId = mediaSourceId ?: bySource.keys.first()
            return TrickplaySource(item.id, sourceId, info, serverUrl)
        }
    }
}

/**
 * Decodes single thumbnails out of trickplay sheets. A sheet is fetched once
 * (a few hundred KB of JPEG) and kept as a region decoder, so each preview is
 * a ~320x180 decode instead of a 3200x1800 one; only the last few sheets are
 * kept, so memory stays flat however long the film.
 */
class TrickplayThumbnails(private val http: OkHttpClient) {
    private val mutex = Mutex()
    private val sheets = object : LruCache<String, BitmapRegionDecoder>(MAX_SHEETS) {
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: BitmapRegionDecoder, newValue: BitmapRegionDecoder?) {
            oldValue.recycle()
        }
    }
    private val options = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 }

    /** Null when the sheet can't be had (no network, not generated): the preview just shows the time. */
    suspend fun thumbnail(source: TrickplaySource, positionMs: Long): ImageBitmap? = withContext(Dispatchers.IO) {
        val cell = source.locate(positionMs)
        val url = cell.sheetUrl
        val rect = Rect(cell.x, cell.y, cell.x + cell.width, cell.y + cell.height)
        try {
            mutex.withLock {
                val decoder = sheets.get(url) ?: fetch(url)?.also { sheets.put(url, it) } ?: return@withLock null
                decoder.decodeRegion(rect, options)?.asImageBitmap()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun fetch(url: String): BitmapRegionDecoder? {
        val bytes = http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.bytes() ?: return null
        }
        return decoderFor(bytes)
    }

    @Suppress("DEPRECATION")
    private fun decoderFor(bytes: ByteArray): BitmapRegionDecoder? =
        if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
        else BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)

    fun clear() = sheets.evictAll()

    private companion object {
        const val MAX_SHEETS = 3
    }
}
