package com.veeha.fastfin.data

import androidx.compose.runtime.Immutable

/**
 * Artwork URLs, sized for where they are drawn.
 *
 * Spotifast picks the closest known artwork size per surface (64 px rows,
 * 300 px grids, 640 px headers) so one download serves many views. Same idea
 * here: the requested width is rounded up to a small set of buckets, so a
 * poster fetched for Home is a cache hit in the library grid and in search.
 * `tag` is the image's content hash, so a URL never goes stale and the disk
 * cache can keep it forever. Items without that image type get no URL at all
 * rather than a request that 404s.
 */
@Immutable
class ImageUrls(private val server: String) {

    fun url(id: String, type: String, tag: String?, widthPx: Int, index: Int? = null): String = buildString {
        append(server).append("/Items/").append(id).append("/Images/").append(type)
        if (index != null) append('/').append(index)
        append("?maxWidth=").append(bucket(widthPx)).append("&quality=90")
        if (tag != null) append("&tag=").append(tag)
    }

    private fun tagged(item: Item, type: String, widthPx: Int): String? {
        val tags = item.imageTags ?: return url(item.id, type, null, widthPx)
        return tags[type]?.let { url(item.id, type, it, widthPx) }
    }

    fun primary(item: Item, widthPx: Int): String? = tagged(item, "Primary", widthPx)

    /** 2:3 poster. Episodes (whose Primary is a 16:9 still) borrow their series'. */
    fun poster(item: Item, widthPx: Int): String? = when {
        item.isEpisode && item.seriesId != null -> url(item.seriesId, "Primary", item.seriesPrimaryImageTag, widthPx)
        item.imageTags != null && item.imageTags["Primary"] == null && item.seriesId != null ->
            url(item.seriesId, "Primary", item.seriesPrimaryImageTag, widthPx)
        else -> primary(item, widthPx)
    }

    fun backdrop(item: Item, widthPx: Int): String? {
        item.backdropImageTags?.firstOrNull()?.let { return url(item.id, "Backdrop", it, widthPx, 0) }
        val parent = item.parentBackdropItemId ?: return null
        return url(parent, "Backdrop", item.parentBackdropImageTags?.firstOrNull(), widthPx, 0)
    }

    fun logo(item: Item, widthPx: Int): String? {
        item.imageTags?.get("Logo")?.let { return url(item.id, "Logo", it, widthPx) }
        val parent = item.parentLogoItemId ?: return null
        return url(parent, "Logo", item.parentLogoImageTag, widthPx)
    }

    /** 16:9 still: an episode's own image, otherwise a backdrop or thumb. */
    fun landscape(item: Item, widthPx: Int): String? = when {
        item.isEpisode -> primary(item, widthPx)
        else -> backdrop(item, widthPx)
            ?: item.imageTags?.get("Thumb")?.let { url(item.id, "Thumb", it, widthPx) }
            ?: primary(item, widthPx)
    }

    companion object {
        private val BUCKETS = intArrayOf(64, 160, 320, 480, 720, 960, 1280, 1920)
        fun bucket(px: Int): Int = BUCKETS.firstOrNull { it >= px } ?: BUCKETS.last()
    }
}
