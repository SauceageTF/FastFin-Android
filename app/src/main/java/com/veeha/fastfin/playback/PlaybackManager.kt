@file:OptIn(UnstableApi::class)

package com.veeha.fastfin.playback

import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.compose.runtime.Immutable
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.veeha.fastfin.AppGraph
import com.veeha.fastfin.MainActivity
import com.veeha.fastfin.data.Item
import com.veeha.fastfin.data.Segment
import com.veeha.fastfin.data.SegmentKind
import com.veeha.fastfin.data.SkipMode
import com.veeha.fastfin.data.SUBTITLES_OFF_PREF
import com.veeha.fastfin.data.TICKS_PER_MS
import com.veeha.fastfin.data.displayTitle
import com.veeha.fastfin.data.isEpisode
import com.veeha.fastfin.data.isPlayable
import com.veeha.fastfin.data.resumeMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Immutable
data class PlayerError(val message: String, val detail: String? = null)

/** Everything the player UI draws except the clock, which the UI samples
 * itself only while it is on screen. */
@Immutable
data class PlayerUi(
    val item: Item,
    val source: PlaybackSource? = null,
    val error: PlayerError? = null,
    /** Full screen (true) or the mini player (false). */
    val expanded: Boolean = true,
    val notice: String? = null,
)

/** The episodes before and after the one playing; null at either end of a show, or for a film. */
@Immutable
data class Adjacent(val previous: Item? = null, val next: Item? = null)

/** The next episode, offered at the credits. While `deadline` (elapsedRealtime)
 * is set it is counting down; null means waiting for a tap. */
@Immutable
data class UpNext(val item: Item, val deadline: Long?)

/**
 * One ExoPlayer for the whole app, outliving screens and the activity.
 *
 * The full-screen player, the mini player and Picture in Picture are three
 * layouts of this one player and its one video surface: collapsing to the
 * mini player never re-buffers or re-opens a decoder. The player is created on
 * first use and released on close, so an idle app holds no codec and no
 * buffers (Spotifast: idle work and memory are product features).
 *
 * Optimistic, as in Spotifast: [open] shows the player the moment it is
 * tapped, with the artwork already known, while PlaybackInfo negotiates
 * behind it.
 */
class PlaybackManager(private val app: Application, private val graph: AppGraph) {
    private val scope get() = graph.scope

    private val _state = MutableStateFlow<PlayerUi?>(null)
    val state: StateFlow<PlayerUi?> = _state.asStateFlow()

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    /** Display aspect ratio, pixel aspect included (anamorphic DVDs). */
    private val _videoAspect = MutableStateFlow(16f / 9f)
    val videoAspect: StateFlow<Float> = _videoAspect.asStateFlow()

    private val _firstFrame = MutableStateFlow(false)
    val firstFrame: StateFlow<Boolean> = _firstFrame.asStateFlow()

    /** The intro, recap or credits being played right now, if the server knows them. */
    private val _segment = MutableStateFlow<Segment?>(null)
    val segment: StateFlow<Segment?> = _segment.asStateFlow()

    /** The next episode card: shown at the credits or the end, counting down while `deadline` is set. */
    private val _upNext = MutableStateFlow<UpNext?>(null)
    val upNext: StateFlow<UpNext?> = _upNext.asStateFlow()

    /** Playback speed. Kept across episodes while the player is open; back to 1× when it closes. */
    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private var segments: List<Segment> = emptyList()
    /** The episodes either side of this one, for Previous/Next and the Up Next card. */
    private val _adjacent = MutableStateFlow(Adjacent())
    val adjacent: StateFlow<Adjacent> = _adjacent.asStateFlow()

    /** The next episode, negotiated and queued in the player during the credits. */
    private class Prepared(val item: Item, val source: PlaybackSource, val startMs: Long, val selection: Selection)
    private var prepared: Prepared? = null
    private var prepareJob: Job? = null
    /** Player callbacks at segment boundaries. */
    private val boundaries = ArrayList<PlayerMessage>()
    /** The episode whose end has been handled, so pause-at-end and ENDED don't both act. */
    private var endedItemId: String? = null
    private var extrasJob: Job? = null
    private var countdownJob: Job? = null
    /** Segments already auto-skipped in this episode, by start: seeking back in is deliberate. */
    private val autoSkipped = HashSet<Long>()
    private var upNextDismissed = false
    /** Episodes played back to back with nobody touching anything. */
    private var autoplayChain = 0

    private var mediaSession: MediaSession? = null
    /** For PlaybackService; null while nothing is open. */
    val session: MediaSession? get() = mediaSession
    private var sessionPlayer: SessionPlayer? = null
    private var controller: ListenableFuture<MediaController>? = null
    private var adjacentJob: Job? = null
    private var loadJob: Job? = null
    private var progressJob: Job? = null
    private var noticeJob: Job? = null

    /** Item and source announced with /Sessions/Playing; cleared once stopped. */
    private var reporting: Pair<Item, PlaybackSource>? = null
    private var selection = Selection()
    private var caps: DeviceCapabilities? = null
    /** 0 = negotiated, 1 = server-only after a direct play failure, 2 = H.264 fallback. */
    private var attempt = 0
    private var ioRetries = 0
    private var tracksApplied = false
    private val diagnostics = ArrayList<String>()

    // MARK: Opening and closing

    fun open(item: Item, restart: Boolean = false) {
        // Containers (series, seasons, box sets) have nothing to stream;
        // Jellyfin answers PlaybackInfo for them with HTTP 400. Callers route
        // those to their episode lists, so this is only a safety net.
        if (!item.isPlayable) return
        val current = _state.value
        if (!restart && current != null && current.item.id == item.id && current.error == null) {
            expand()
            return
        }
        // Someone chose this: the "still watching?" count starts over.
        autoplayChain = 0
        start(item, restart, expanded = true)
    }

    private fun start(item: Item, restart: Boolean, expanded: Boolean) {
        stopSession()
        selection = Selection()
        attempt = 0
        ioRetries = 0
        diagnostics.clear()
        _firstFrame.value = false
        _videoAspect.value = 16f / 9f
        _state.value = PlayerUi(item = item, expanded = expanded)
        loadJob = scope.launch { load(item, startMs = if (restart) 0L else null, allowDirectPlay = true) }
    }

    fun expand() = _state.update { it?.copy(expanded = true) }

    fun collapse() = _state.update { it?.copy(expanded = false) }

    fun close() {
        stopSession()
        graph.trickplay.clear()
        _speed.value = 1f
        _state.value = null
        val player = _player.value
        _player.value = null
        adjacentJob?.cancel()
        controller?.let(MediaController::releaseFuture)
        controller = null
        sessionPlayer = null
        mediaSession?.release()
        mediaSession = null
        player?.release()
        app.stopService(Intent(app, PlaybackService::class.java))
        _isPlaying.value = false
        _isBuffering.value = false
        _firstFrame.value = false
    }

    fun retry() {
        val state = _state.value ?: return
        val position = _player.value?.currentPosition ?: 0L
        attempt = 0
        ioRetries = 0
        _firstFrame.value = false
        _state.value = state.copy(source = null, error = null, expanded = true)
        loadJob?.cancel()
        loadJob = scope.launch { load(state.item, startMs = position.takeIf { it > 0 }, allowDirectPlay = true) }
    }

    // MARK: Transport

    fun pause() {
        _player.value?.pause()
    }

    fun togglePlay() {
        val player = _player.value ?: return
        autoplayChain = 0
        if (player.playbackState == Player.STATE_ENDED) {
            _upNext.value = null
            countdownJob?.cancel()
            player.seekTo(0)
        }
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekBy(deltaMs: Long) {
        val player = _player.value ?: return
        autoplayChain = 0
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val target = player.currentPosition + deltaMs
        player.seekTo(if (duration != null) target.coerceIn(0, duration - 500) else target.coerceAtLeast(0))
        checkPosition()
    }

    fun seekTo(positionMs: Long) {
        autoplayChain = 0
        _player.value?.seekTo(positionMs.coerceAtLeast(0))
        checkPosition()
    }

    fun setSpeed(speed: Float) {
        _speed.value = speed
        _player.value?.setPlaybackSpeed(speed)
    }

    // MARK: Intros, credits and the next episode

    /** "Skip intro" / "Skip credits": jump to the end of the current segment. */
    fun skipSegment() {
        val segment = _segment.value ?: return
        val player = _player.value ?: return
        autoplayChain = 0
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        player.seekTo(if (duration != null) minOf(segment.endMs, duration) else segment.endMs)
        checkPosition()
    }

    /** "Play now" on the card, the countdown running out, or Next on a headset, notification or PiP. */
    fun playNext(automatic: Boolean = false) {
        val next = _upNext.value?.item ?: _adjacent.value.next ?: return
        val player = _player.value
        // Reaching the credits counts as finishing: report the end so the
        // server marks it watched and Next Up moves on.
        reportStopped(finalPosition = player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 })
        autoplayChain = if (automatic) autoplayChain + 1 else 0
        val ready = prepared?.takeIf { it.item.id == next.id }
        val queuedAt = player?.let { p -> (p.currentMediaItemIndex + 1).takeIf { it < p.mediaItemCount && p.getMediaItemAt(it).mediaId == next.id } }
        if (player != null && ready != null && queuedAt != null) {
            // Negotiated during the credits and already buffering behind this
            // one: just move on. The switch-over finishes in onMediaItemTransition.
            player.seekTo(queuedAt, ready.startMs)
            player.play()
        } else {
            // Stay wherever the player is: full screen, mini player or Picture in Picture.
            start(next, restart = false, expanded = _state.value?.expanded ?: true)
        }
    }

    /** Previous on a headset, notification or PiP: the last episode, or the start of this one if it's under way. */
    fun playPrevious() {
        val player = _player.value ?: return
        val previous = _adjacent.value.previous
        if (previous == null || player.currentPosition > RESTART_THRESHOLD_MS) {
            seekTo(0)
            return
        }
        autoplayChain = 0
        start(previous, restart = false, expanded = _state.value?.expanded ?: true)
    }

    /** "Watch credits": hide the card for the rest of this episode; at the very end, stop. */
    fun dismissUpNext() {
        countdownJob?.cancel()
        upNextDismissed = true
        _upNext.value = null
        if (endedItemId == _state.value?.item?.id) scope.launch { close() }
    }

    /** Segments and the neighbouring episodes load alongside stream negotiation and never hold it up. */
    private fun loadExtras(item: Item) {
        extrasJob = scope.launch {
            val found = graph.api.segments(item.id)
            val (previous, next) = if (item.isEpisode) {
                try {
                    graph.api.adjacentEpisodes(item)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null to null
                }
            } else null to null
            if (_state.value?.item?.id != item.id) return@launch
            segments = found
            _adjacent.value = Adjacent(previous, next)
            armSegments()
        }
    }

    /**
     * The player itself calls back exactly at each segment boundary, plus one
     * check after any seek (onPositionDiscontinuity): nothing polls, so an
     * episode with intro and credits costs no more than one without.
     */
    private fun armSegments() {
        disarmSegments()
        val player = _player.value ?: return
        // Only once this episode is actually in the player; play() arms it again otherwise.
        if (segments.isEmpty() || player.currentMediaItem?.mediaId != _state.value?.item?.id) return
        val index = player.currentMediaItemIndex
        for (segment in segments) {
            for (edge in longArrayOf(segment.startMs, segment.endMs)) {
                boundaries += player.createMessage { _, _ -> checkPosition(at = edge) }
                    .setLooper(Looper.getMainLooper())
                    .setPosition(index, edge)
                    .setDeleteAfterDelivery(false)
                    .send()
            }
        }
        checkPosition()
    }

    private fun disarmSegments() {
        boundaries.forEach { it.cancel() }
        boundaries.clear()
    }

    /** `at`: the boundary a callback fired for, which the clock may not show to the millisecond yet. */
    private fun checkPosition(at: Long? = null) {
        val player = _player.value ?: return
        if (segments.isEmpty()) return
        val position = at ?: player.currentPosition
        val active = segments.firstOrNull { position in it }
        if (active == _segment.value) return
        _segment.value = active
        when (active?.kind) {
            SegmentKind.Intro, SegmentKind.Recap -> if (graph.settings.value.skipIntros == SkipMode.Auto && autoSkipped.add(active.startMs)) {
                player.seekTo(active.endMs)
                showNotice(if (active.kind == SegmentKind.Intro) "Skipped intro" else "Skipped recap")
            }
            SegmentKind.Outro, SegmentKind.Preview -> {
                val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: active.endMs
                countDown((minOf(active.endMs, duration) - position).coerceIn(MIN_COUNTDOWN_MS, COUNTDOWN_MS))
            }
            // Seeking back out of the credits takes the card away again. Credits
            // that run to the last frame "end" right as the episode does: not that.
            null -> if (player.playbackState != Player.STATE_ENDED && !nearEnd(player, position)) {
                countdownJob?.cancel()
                _upNext.value = null
            }
        }
    }

    private fun nearEnd(player: ExoPlayer, position: Long): Boolean {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return false
        return position >= duration - END_TOLERANCE_MS
    }

    /** Shows the next episode card, counting down unless someone needs to say they're still there. */
    private fun countDown(ms: Long) {
        val next = _adjacent.value.next ?: return
        if (!graph.settings.value.autoplayNext || upNextDismissed) return
        countdownJob?.cancel()
        prepareNext(next)
        if (autoplayChain >= MAX_AUTOPLAY_CHAIN) {
            _upNext.value = UpNext(next, deadline = null)
            return
        }
        _upNext.value = UpNext(next, deadline = SystemClock.elapsedRealtime() + ms)
        countdownJob = scope.launch {
            delay(ms)
            playNext(automatic = true)
        }
    }

    private fun resumeCountdown() {
        val card = _upNext.value ?: return
        if (card.deadline != null) return
        val credits = _segment.value?.takeIf { it.kind == SegmentKind.Outro || it.kind == SegmentKind.Preview } ?: return
        val player = _player.value ?: return
        countDown((credits.endMs - player.currentPosition).coerceIn(MIN_COUNTDOWN_MS, COUNTDOWN_MS))
    }

    /**
     * During the credits: fetch the next episode, negotiate its stream, and
     * queue it behind this one so the player buffers its opening now. When
     * the countdown ends it starts almost at once. Not ready in time is fine:
     * playNext then negotiates the usual way.
     */
    private fun prepareNext(next: Item) {
        if (prepareJob != null || prepared?.item?.id == next.id) return
        val session = graph.sessions.current ?: return
        prepareJob = scope.launch {
            try {
                val item = graph.api.item(next.id)
                val start = resumePoint(item)
                val selection = preferredSelection(item)
                val source = Negotiator.negotiate(
                    graph.api, session, graph.capabilities.await(), graph.settings.value, item.id, start, selection, allowDirectPlay = true,
                )
                val player = _player.value
                if (player == null || _adjacent.value.next?.id != next.id) {
                    stopEncoding(source)
                    return@launch
                }
                player.addMediaItem(mediaItem(item, source))
                prepared = Prepared(item, source, start, selection)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Falls back to a normal start.
            } finally {
                prepareJob = null
            }
        }
    }

    /** Forget the queued episode: out of the playlist, and its transcode (if any) stopped. */
    private fun dropPrepared() {
        prepareJob?.cancel()
        prepareJob = null
        val ready = prepared ?: return
        prepared = null
        _player.value?.let { player ->
            for (i in player.mediaItemCount - 1 downTo 0) {
                if (i != player.currentMediaItemIndex && player.getMediaItemAt(i).mediaId == ready.item.id) player.removeMediaItem(i)
            }
        }
        stopEncoding(ready.source)
    }

    private fun stopEncoding(source: PlaybackSource) {
        if (source.playMethod == PlayMethod.DirectPlay) return
        val deviceId = graph.sessions.current?.deviceId ?: return
        scope.launch {
            graph.api.send("DELETE", "/Videos/ActiveEncodings", query = mapOf("deviceId" to deviceId, "playSessionId" to source.playSessionId))
        }
    }

    /** The queued episode just became the current one: move everything over. The player, decoder and surface stay. */
    private fun completeHandoff(ready: Prepared) {
        val player = _player.value ?: return
        prepared = null
        resetEpisode()
        selection = ready.selection
        attempt = 0
        ioRetries = 0
        diagnostics.clear()
        tracksApplied = false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        while (player.currentMediaItemIndex > 0) player.removeMediaItem(0)
        _state.update { it?.copy(item = ready.item, source = ready.source, error = null, notice = null) }
        reportStart(ready.item, ready.source)
        loadExtras(ready.item)
    }

    /** An episode reached its end: the countdown, the waiting card, or closing. Once per episode. */
    private fun onItemEnded() {
        val item = _state.value?.item ?: return
        if (endedItemId == item.id) return
        endedItemId = item.id
        reportStopped(finalPosition = _player.value?.duration?.takeIf { it > 0 })
        val next = _adjacent.value.next
        when {
            next == null || !graph.settings.value.autoplayNext ->
                // Posted: never release a player from inside its own callback.
                scope.launch { close() }
            // Already counting down from the credits: let it finish.
            _upNext.value?.deadline != null -> Unit
            // They chose to watch the credits; now offer the next one, without a timer.
            upNextDismissed -> {
                upNextDismissed = false
                prepareNext(next)
                _upNext.value = UpNext(next, deadline = null)
            }
            // No credits marker: count down from the very end instead.
            else -> countDown(END_COUNTDOWN_MS)
        }
    }

    /** Everything that belongs to one episode rather than to the player. */
    private fun resetEpisode() {
        extrasJob?.cancel()
        extrasJob = null
        countdownJob?.cancel()
        disarmSegments()
        segments = emptyList()
        _adjacent.value = Adjacent()
        autoSkipped.clear()
        upNextDismissed = false
        endedItemId = null
        _segment.value = null
        _upNext.value = null
    }

    /** Pausing on the card stops the clock; playing again restarts it. */
    private fun pauseCountdown() {
        val card = _upNext.value ?: return
        if (card.deadline == null) return
        countdownJob?.cancel()
        _upNext.value = card.copy(deadline = null)
    }

    // MARK: Loading

    private suspend fun load(seed: Item, startMs: Long?, allowDirectPlay: Boolean) {
        val session = graph.sessions.current ?: return
        try {
            // A fresh copy for the true resume point (the list copy may be minutes
            // old) and its streams, which language preferences are matched against.
            val item = if (startMs == null || seed.mediaStreams == null) {
                try {
                    graph.api.item(seed.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    seed
                }
            } else seed
            if (extrasJob == null) loadExtras(item)
            // Only before the first negotiation; a track picked in the player wins.
            if (selection == Selection()) selection = preferredSelection(item)
            val start = startMs ?: resumePoint(item)
            val capabilities = graph.capabilities.await().also { caps = it }
            val source = Negotiator.negotiate(
                graph.api, session, capabilities, graph.settings.value, item.id, start, selection, allowDirectPlay,
            )
            if (_state.value?.item?.id != seed.id) return
            _state.update { it?.copy(item = item, source = source, error = null) }
            play(item, source, start)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e.message ?: "Couldn't start playback.", (e as? PlaybackError)?.reasons?.joinToString(", ")?.ifEmpty { null })
        }
    }

    /** Where to start: the resume point, unless it's at the very start or the very end. */
    private fun resumePoint(item: Item): Long =
        item.resumeMs.takeIf { it > 2_000 && (item.runTimeTicks ?: Long.MAX_VALUE) / TICKS_PER_MS > it + 5_000 } ?: 0L

    /** Languages: this show's remembered choice, else the global setting, else the server's. */
    private fun preferredSelection(item: Item): Selection {
        val streams = item.mediaStreams ?: return Selection()
        val remembered = item.seriesId?.let(graph.tracks::get)
        val settings = graph.settings.value
        return TrackPreferences.select(
            streams,
            audio = remembered?.audio ?: settings.audioLanguage,
            subtitle = remembered?.subtitle ?: settings.subtitleLanguage,
        )
    }

    private fun play(item: Item, source: PlaybackSource, startMs: Long) {
        val player = ensurePlayer()
        dropPrepared()
        tracksApplied = false
        _firstFrame.value = false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        player.setMediaItem(mediaItem(item, source), startMs)
        player.prepare()
        player.play()
        reportStart(item, source)
        armSegments()
    }

    /**
     * Re-attaches the same stream with a different side-loaded subtitle (or
     * none) at `positionMs`. Same play session, no server round trip; it
     * resumes in whatever play/pause state it was in.
     */
    private fun reloadSource(item: Item, source: PlaybackSource, positionMs: Long) {
        val player = _player.value ?: return
        // setMediaItem replaces the playlist, queued next episode included.
        dropPrepared()
        val playWhenReady = player.playWhenReady
        _state.update { it?.copy(source = source) }
        tracksApplied = false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        player.setMediaItem(mediaItem(item, source), positionMs)
        player.prepare()
        player.playWhenReady = playWhenReady
        armSegments()
    }

    /** The URL whose fetch failed: the file, a playlist, a segment or a subtitle. */
    private fun failingUri(error: PlaybackException): String? {
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is HttpDataSource.HttpDataSourceException) return cause.dataSpec.uri.toString()
            cause = cause.cause
        }
        return null
    }

    private fun httpStatus(error: PlaybackException): String? {
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) return "HTTP ${cause.responseCode}"
            cause = cause.cause
        }
        return null
    }

    private fun sameResource(a: String, b: String) = a.substringBefore('?') == b.substringBefore('?')

    /** Path only: queries can carry tokens and never belong in diagnostics. */
    private fun pathOf(url: String): String = "/" + url.substringBefore('?').substringAfter("://").substringAfter('/', "")

    private fun mediaItem(item: Item, source: PlaybackSource): MediaItem {
        // Only the selected external subtitle is side-loaded. ExoPlayer fetches
        // every side-loaded file up front and a single failed fetch fails the
        // whole video; a transcoded episode can list a dozen tracks, each an
        // ffmpeg extraction on the server. Others are swapped in on demand.
        val subtitles = source.subtitleTracks
            .filter { it.index == source.selectedSubtitle && it.delivery == Delivery.External && it.deliveryUrl != null }
            .map { track ->
                MediaItem.SubtitleConfiguration.Builder(track.deliveryUrl!!.toUri())
                    .setId(externalId(track.index))
                    .setLabel(externalId(track.index))
                    .setLanguage(track.language)
                    .setMimeType(subtitleMime(track))
                    .setSelectionFlags(0)
                    .build()
            }
        val artwork = graph.sessions.current?.let { s ->
            com.veeha.fastfin.data.ImageUrls(s.serverUrl).poster(item, 480)
        }
        return MediaItem.Builder()
            .setUri(source.url)
            .setMediaId(item.id)
            .setMimeType(if (source.isHls) MimeTypes.APPLICATION_M3U8 else null)
            .setSubtitleConfigurations(subtitles)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.displayTitle)
                    .setArtist(item.seriesName)
                    .setArtworkUri(artwork?.toUri())
                    .build()
            )
            .build()
    }

    private fun ensurePlayer(): ExoPlayer {
        _player.value?.let { return it }
        // The app's OkHttp client: same connection pool as the API, and the
        // auth interceptor covers direct play, playlists and segments.
        val dataSource = DefaultDataSource.Factory(app, OkHttpDataSource.Factory(graph.http))
        val renderers = DefaultRenderersFactory(app)
            // If a hardware decoder refuses to initialise, try the next one
            // before giving up on direct play.
            .setEnableDecoderFallback(true)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_000, 2_000)
            .build()
        val player = ExoPlayer.Builder(app, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player.addListener(listener)
        player.setPlaybackSpeed(_speed.value)
        // A queued next episode never starts by itself: the Up Next countdown
        // (and "Watch credits", and the still-watching check) decide when.
        player.pauseAtEndOfMediaItems = true
        // System media session: the notification and lock screen (through
        // PlaybackService), Bluetooth/headset buttons, Assistant. Its player adds
        // previous/next episode on top of the real one.
        val forSession = SessionPlayer(player)
        sessionPlayer = forSession
        mediaSession = MediaSession.Builder(app, forSession)
            .setSessionActivity(
                PendingIntent.getActivity(
                    app, 0,
                    Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
        adjacentJob = scope.launch { _adjacent.collect { forSession.refresh() } }
        // Connecting a controller starts PlaybackService, which shows the notification.
        controller = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        _player.value = player
        return player
    }

    /**
     * What the system sees: the real player, plus Previous and Next meaning
     * episodes. Next appears only when there is a next episode; Previous
     * restarts the episode, or goes back one near its start.
     */
    private inner class SessionPlayer(player: Player) : ForwardingSimpleBasePlayer(player) {
        fun refresh() = invalidateState()

        override fun getState(): State {
            val state = super.getState()
            val hasNext = _adjacent.value.next != null
            val commands = state.availableCommands.buildUpon()
                .addIf(Player.COMMAND_SEEK_TO_NEXT, hasNext)
                .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, hasNext)
                .removeIf(Player.COMMAND_SEEK_TO_NEXT, !hasNext)
                .removeIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, !hasNext)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .build()
            return state.buildUpon().setAvailableCommands(commands).build()
        }

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            when (seekCommand) {
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> playNext()
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> playPrevious()
                else -> return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
            }
            return Futures.immediateVoidFuture()
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            // Pause and resume are the moments the server most wants to hear about.
            reportProgress()
            if (isPlaying) {
                startProgressLoop()
                resumeCountdown()
            } else {
                progressJob?.cancel()
                // Stopping at the end is not "pausing on the card".
                if (endedItemId != _state.value?.item?.id) pauseCountdown()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _isBuffering.value = playbackState == Player.STATE_BUFFERING
            if (playbackState == Player.STATE_READY) ioRetries = 0
            if (playbackState == Player.STATE_ENDED) onItemEnded()
        }

        // With an episode queued, the player pauses at the end of this one
        // (pauseAtEndOfMediaItems) instead of rolling on: the countdown decides.
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) onItemEnded()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val ready = prepared ?: return
            if (mediaItem?.mediaId == ready.item.id) completeHandoff(ready)
        }

        // Seeks jump over boundary callbacks; look where they landed.
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) checkPosition()
        }

        override fun onPlayerError(error: PlaybackException) = handleError(error)

        override fun onTracksChanged(tracks: Tracks) {
            if (!tracksApplied && !tracks.isEmpty) {
                tracksApplied = true
                applyInitialTracks()
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                _videoAspect.value = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
        }

        override fun onRenderedFirstFrame() {
            _firstFrame.value = true
        }
    }

    // MARK: Failure handling: retry, then step down one tier at a time

    private fun handleError(error: PlaybackException) {
        val state = _state.value ?: return
        val source = state.source ?: return
        val player = _player.value ?: return
        val position = player.currentPosition
        val failedUri = failingUri(error)

        // A subtitle that won't load is not a reason to stop the video: drop it.
        val badSubtitle = failedUri?.let { uri ->
            source.subtitleTracks.firstOrNull { it.deliveryUrl != null && sameResource(it.deliveryUrl, uri) }
        }
        if (badSubtitle != null && source.selectedSubtitle == badSubtitle.index) {
            diagnostics += "Subtitle ${badSubtitle.index}: ${error.errorCodeName} · ${httpStatus(error) ?: ""} ${pathOf(failedUri.orEmpty())}"
            selection = selection.copy(subtitle = SUBTITLES_OFF)
            reloadSource(state.item, source.copy(selectedSubtitle = null), position)
            showNotice("Couldn't load ${badSubtitle.title} subtitles")
            return
        }

        // A network blip mid-film is not a reason to transcode: re-prepare in place.
        val isNetwork = error.errorCode in 2000..2999
        if (isNetwork && ioRetries < 2) {
            ioRetries++
            scope.launch {
                delay(1_000L * ioRetries)
                if (_state.value?.source == source) player.prepare()
            }
            return
        }

        loadJob?.cancel()
        loadJob = scope.launch {
            // Probe what actually failed (a playlist, a segment, the file), and
            // name its path (never its query, which can carry a token).
            val target = failedUri ?: source.url
            val probe = graph.api.probe(target)
            diagnostics += "${source.playMethod.name}${if (source.isFallback) " (fallback)" else ""}: " +
                "${error.errorCodeName} · ${pathOf(target)} · $probe"
            when {
                attempt == 0 && source.playMethod == PlayMethod.DirectPlay -> {
                    // The decoder disagreed with what it advertised. Let the
                    // server remux or transcode instead.
                    attempt = 1
                    reportStopped()
                    _state.update { it?.copy(source = null) }
                    load(state.item, startMs = position, allowDirectPlay = false)
                }
                !source.isFallback -> {
                    attempt = 2
                    reportStopped()
                    val session = graph.sessions.current ?: return@launch
                    val fallback = Negotiator.fallback(graph.api, session, state.item.id, source, position)
                    _state.update { it?.copy(source = fallback) }
                    play(state.item, fallback, position)
                }
                else -> fail("Couldn't play this video.", error.message)
            }
        }
    }

    private fun fail(message: String, detail: String?) {
        val source = _state.value?.source
        val lines = listOfNotNull(detail, source?.summary) + source?.transcodeReasons.orEmpty() + diagnostics
        progressJob?.cancel()
        reportStopped()
        _player.value?.stop()
        _state.update { it?.copy(error = PlayerError(message, lines.distinct().joinToString("\n").ifBlank { null }), expanded = true) }
    }

    // MARK: Audio and subtitle tracks

    /**
     * During direct play every track is inside the file ExoPlayer is reading,
     * so switching is instant and local, no reload, unlike iOS where every
     * switch goes through the server. Only a track the device can't decode,
     * or one the server must burn in, triggers a re-negotiation.
     */
    /** A track picked by hand during an episode becomes that show's choice. */
    private inline fun rememberChoice(save: (seriesId: String) -> Unit) {
        _state.value?.item?.seriesId?.let(save)
    }

    fun selectAudio(index: Int) {
        val source = _state.value?.source ?: return
        if (index == source.selectedAudio) return
        val track = source.audioTracks.firstOrNull { it.index == index } ?: return
        rememberChoice { series -> track.language?.let { graph.tracks.rememberAudio(series, it) } }
        val decodable = track.codec != null && caps?.audioCodecs?.contains(track.codec) == true
        if (source.playMethod == PlayMethod.DirectPlay && decodable && applyTrack(C.TRACK_TYPE_AUDIO, track, source)) {
            selection = selection.copy(audio = index)
            _state.update { it?.copy(source = source.copy(selectedAudio = index)) }
            reportProgress()
            return
        }
        renegotiate(selection.copy(audio = index), "Switching audio…")
    }

    fun selectSubtitle(index: Int?) {
        val source = _state.value?.source ?: return
        if (index == source.selectedSubtitle) return
        val current = source.subtitleTracks.firstOrNull { it.index == source.selectedSubtitle }
        val track = index?.let { i -> source.subtitleTracks.firstOrNull { it.index == i } }
        rememberChoice { series ->
            if (index == null) graph.tracks.rememberSubtitle(series, SUBTITLES_OFF_PREF)
            else track?.language?.let { graph.tracks.rememberSubtitle(series, it) }
        }
        val local = when {
            // Burned-in subtitles can only be removed by the server.
            current?.delivery == Delivery.Encode -> false
            index == null -> disableText()
            track == null -> false
            // Side-loaded on demand: re-attach the same stream with this subtitle,
            // at the current position. No server round trip, no new transcode.
            track.delivery == Delivery.External && track.deliveryUrl != null -> {
                val item = _state.value?.item ?: return
                reloadSource(item, source.copy(selectedSubtitle = index), _player.value?.currentPosition ?: 0L)
                true
            }
            track.delivery == Delivery.Embed && source.playMethod == PlayMethod.DirectPlay -> applyTrack(C.TRACK_TYPE_TEXT, track, source)
            else -> false
        }
        if (local) {
            selection = selection.copy(subtitle = index ?: SUBTITLES_OFF)
            _state.update { it?.copy(source = source.copy(selectedSubtitle = index)) }
            reportProgress()
            return
        }
        renegotiate(selection.copy(subtitle = index ?: SUBTITLES_OFF), "Loading subtitles…")
    }

    private fun renegotiate(next: Selection, notice: String) {
        val state = _state.value ?: return
        val player = _player.value
        val position = player?.currentPosition ?: 0L
        selection = next
        progressJob?.cancel()
        // Stopped first: the old job is killed, so its stream must not be
        // mistaken for a failure and walked down the fallback chain.
        reportStopped()
        player?.stop()
        attempt = 0
        ioRetries = 0
        showNotice(notice)
        loadJob?.cancel()
        loadJob = scope.launch { load(state.item, startMs = position, allowDirectPlay = true) }
    }

    /** Applies the server's default (or the user's) choice once tracks are known. */
    private fun applyInitialTracks() {
        val source = _state.value?.source ?: return
        if (source.playMethod == PlayMethod.DirectPlay) {
            source.audioTracks.firstOrNull { it.index == source.selectedAudio }?.let { applyTrack(C.TRACK_TYPE_AUDIO, it, source) }
        }
        val subtitle = source.subtitleTracks.firstOrNull { it.index == source.selectedSubtitle }
        when {
            subtitle == null || subtitle.delivery == Delivery.Encode -> disableText()
            subtitle.delivery == Delivery.External -> applyTrack(C.TRACK_TYPE_TEXT, subtitle, source)
            source.playMethod == PlayMethod.DirectPlay -> applyTrack(C.TRACK_TYPE_TEXT, subtitle, source)
            else -> enableFirstText()
        }
    }

    /**
     * Maps a Jellyfin stream to ExoPlayer's track group. External subtitles
     * carry our id; embedded tracks are matched by their order within the
     * type (both list them in container order), checked against language in
     * case the extractor skipped a track it can't read.
     */
    private fun applyTrack(type: Int, option: TrackOption, source: PlaybackSource): Boolean {
        val player = _player.value ?: return false
        val groups = player.currentTracks.groups.filter { it.type == type }
        if (groups.isEmpty()) return false
        val group = if (option.delivery == Delivery.External) {
            groups.firstOrNull { it.hasFormatId(externalId(option.index)) }
        } else {
            val all = if (type == C.TRACK_TYPE_AUDIO) source.audioTracks else source.subtitleTracks
            val ordinal = all.filter { it.delivery == Delivery.Embed }.indexOfFirst { it.index == option.index }
            val embedded = groups.filterNot { it.hasFormatId(EXTERNAL_PREFIX) }
            val language = option.language?.let(::normalizeLanguage)
            val byOrder = embedded.getOrNull(ordinal)
            when {
                byOrder != null && (language == null || byOrder.language() == null || byOrder.language() == language) -> byOrder
                language != null -> embedded.firstOrNull { it.language() == language } ?: byOrder
                else -> byOrder
            }
        } ?: return false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
        return true
    }

    private fun disableText(): Boolean {
        val player = _player.value ?: return false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        return true
    }

    private fun enableFirstText() {
        val player = _player.value ?: return
        val group = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && !it.hasFormatId(EXTERNAL_PREFIX) }
            ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
    }

    private fun Tracks.Group.hasFormatId(marker: String): Boolean =
        (0 until length).any { i -> getTrackFormat(i).let { (it.id?.contains(marker) == true) || (it.label?.contains(marker) == true) } }

    private fun Tracks.Group.language(): String? = getTrackFormat(0).language?.let(::normalizeLanguage)

    /** Media3 folds ISO 639-2 B/T variants (fre/fra, ger/deu) and 639-1 into one form. */
    private fun normalizeLanguage(code: String): String? {
        val normalized: String = Util.normalizeLanguageCode(code)
        return normalized.takeIf { it != "und" && it.isNotBlank() }
    }

    private fun externalId(index: Int) = "$EXTERNAL_PREFIX$index;"

    private fun subtitleMime(track: TrackOption): String {
        val extension = track.deliveryUrl?.substringBefore('?')?.substringAfterLast('.')?.lowercase() ?: track.codec
        return when (extension) {
            "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "ttml", "xml", "dfxp" -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.TEXT_VTT
        }
    }

    private fun showNotice(text: String) {
        noticeJob?.cancel()
        _state.update { it?.copy(notice = text) }
        noticeJob = scope.launch {
            delay(2_800)
            _state.update { it?.copy(notice = null) }
        }
    }

    // MARK: Server reporting
    //
    // Progress feeds Continue Watching; Stopped plus ActiveEncodings kills the
    // ffmpeg job so transcodes never pile up on the server. Reporting is
    // event-driven: on start, pause, resume, track change and stop, plus one
    // report every ten seconds only while actually playing.

    private fun reportBody(item: Item, source: PlaybackSource, positionMs: Long, paused: Boolean) = buildJsonObject {
        put("ItemId", item.id)
        put("MediaSourceId", source.mediaSourceId)
        put("PlaySessionId", source.playSessionId)
        put("PlayMethod", source.playMethod.name)
        put("PositionTicks", positionMs * TICKS_PER_MS)
        put("IsPaused", paused)
        put("IsMuted", false)
        put("CanSeek", true)
        source.selectedAudio?.let { put("AudioStreamIndex", it) }
        put("SubtitleStreamIndex", source.selectedSubtitle ?: SUBTITLES_OFF)
        put("RepeatMode", "RepeatNone")
        put("PlaybackOrder", "Default")
    }

    private fun reportStart(item: Item, source: PlaybackSource) {
        reporting = item to source
        val body = reportBody(item, source, _player.value?.currentPosition ?: 0L, paused = false)
        scope.launch { graph.api.send("POST", "/Sessions/Playing", body) }
    }

    private fun reportProgress() {
        val (item, started) = reporting ?: return
        val player = _player.value ?: return
        val source = _state.value?.source?.takeIf { it.playSessionId == started.playSessionId } ?: started
        val body = reportBody(item, source, player.currentPosition, paused = !player.isPlaying)
        scope.launch { graph.api.send("POST", "/Sessions/Playing/Progress", body) }
    }

    private fun reportStopped(finalPosition: Long? = null) {
        val (item, source) = reporting ?: return
        reporting = null
        val position = finalPosition ?: _player.value?.currentPosition ?: 0L
        val body = buildJsonObject {
            put("ItemId", item.id)
            put("MediaSourceId", source.mediaSourceId)
            put("PlaySessionId", source.playSessionId)
            put("PositionTicks", position * TICKS_PER_MS)
        }
        val deviceId = graph.sessions.current?.deviceId
        scope.launch {
            graph.api.send("POST", "/Sessions/Playing/Stopped", body)
            // Only now has the server moved the resume point or marked it watched:
            // Continue Watching and episode progress refresh on whatever is showing.
            graph.repo.markStale("home", "item:${item.id}", "episodes:${item.seriesId ?: item.id}")
            if (source.playMethod != PlayMethod.DirectPlay && deviceId != null) {
                graph.api.send("DELETE", "/Videos/ActiveEncodings", query = mapOf("deviceId" to deviceId, "playSessionId" to source.playSessionId))
            }
        }
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                delay(PROGRESS_REPORT_MS)
                reportProgress()
            }
        }
    }

    private fun stopSession() {
        loadJob?.cancel()
        progressJob?.cancel()
        noticeJob?.cancel()
        dropPrepared()
        resetEpisode()
        reportStopped()
        _player.value?.let {
            it.stop()
            it.clearMediaItems()
        }
    }

    private companion object {
        const val PROGRESS_REPORT_MS = 10_000L
        /** Previous restarts the episode instead, once this far in. */
        const val RESTART_THRESHOLD_MS = 5_000L
        const val END_TOLERANCE_MS = 1_500L
        /** Up Next countdown from the credits, shortened if the credits are shorter. */
        const val COUNTDOWN_MS = 10_000L
        const val MIN_COUNTDOWN_MS = 3_000L
        /** Countdown when the episode simply ends (no credits marker). */
        const val END_COUNTDOWN_MS = 8_000L
        /** After this many episodes in a row untouched, ask instead of playing on. */
        const val MAX_AUTOPLAY_CHAIN = 3
        const val EXTERNAL_PREFIX = "ffext-"
    }
}
