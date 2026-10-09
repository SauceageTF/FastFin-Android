@file:OptIn(UnstableApi::class)

package com.veeha.fastfin.playback

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import com.veeha.fastfin.AppGraph
import com.veeha.fastfin.MainActivity
import com.veeha.fastfin.data.Item
import com.veeha.fastfin.data.TICKS_PER_MS
import com.veeha.fastfin.data.displayTitle
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

    private var mediaSession: MediaSession? = null
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
        val current = _state.value
        if (!restart && current != null && current.item.id == item.id && current.error == null) {
            expand()
            return
        }
        stopSession()
        selection = Selection()
        attempt = 0
        ioRetries = 0
        diagnostics.clear()
        _firstFrame.value = false
        _videoAspect.value = 16f / 9f
        _state.value = PlayerUi(item = item)
        loadJob = scope.launch { load(item, startMs = if (restart) 0L else null, allowDirectPlay = true) }
    }

    fun expand() = _state.update { it?.copy(expanded = true) }

    fun collapse() = _state.update { it?.copy(expanded = false) }

    fun close() {
        val item = _state.value?.item
        stopSession()
        _state.value = null
        val player = _player.value
        _player.value = null
        mediaSession?.release()
        mediaSession = null
        player?.release()
        _isPlaying.value = false
        _isBuffering.value = false
        _firstFrame.value = false
        if (item != null) {
            // Resume points moved: refresh these the next time they are shown,
            // keeping the current copy on screen meanwhile.
            graph.repo.markStale("home")
            graph.repo.markStale("item:${item.id}")
            item.seriesId?.let { graph.repo.markStale("episodes:$it") }
        }
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
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekBy(deltaMs: Long) {
        val player = _player.value ?: return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val target = player.currentPosition + deltaMs
        player.seekTo(if (duration != null) target.coerceIn(0, duration - 500) else target.coerceAtLeast(0))
    }

    fun seekTo(positionMs: Long) {
        _player.value?.seekTo(positionMs.coerceAtLeast(0))
    }

    // MARK: Loading

    private suspend fun load(seed: Item, startMs: Long?, allowDirectPlay: Boolean) {
        val session = graph.sessions.current ?: return
        try {
            // A fresh copy for the true resume point; the list copy may be minutes old.
            val item = if (startMs == null) {
                try {
                    graph.api.item(seed.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    seed
                }
            } else seed
            val start = startMs ?: item.resumeMs.takeIf { it > 2_000 && (item.runTimeTicks ?: Long.MAX_VALUE) / TICKS_PER_MS > it + 5_000 } ?: 0L
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

    private fun play(item: Item, source: PlaybackSource, startMs: Long) {
        val player = ensurePlayer()
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
    }

    /**
     * Re-attaches the same stream with a different side-loaded subtitle (or
     * none) at `positionMs`. Same play session, no server round trip; it
     * resumes in whatever play/pause state it was in.
     */
    private fun reloadSource(item: Item, source: PlaybackSource, positionMs: Long) {
        val player = _player.value ?: return
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
        // System media session: Bluetooth/headset buttons, Assistant, and the
        // play/pause controls Android 13+ draws in Picture in Picture.
        mediaSession = MediaSession.Builder(app, player)
            .setSessionActivity(
                PendingIntent.getActivity(
                    app, 0,
                    Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
        _player.value = player
        return player
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            // Pause and resume are the moments the server most wants to hear about.
            reportProgress()
            if (isPlaying) startProgressLoop() else progressJob?.cancel()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _isBuffering.value = playbackState == Player.STATE_BUFFERING
            if (playbackState == Player.STATE_READY) ioRetries = 0
            if (playbackState == Player.STATE_ENDED) {
                reportStopped(finalPosition = _player.value?.duration?.takeIf { it > 0 })
                // Posted: never release a player from inside its own callback.
                scope.launch { close() }
            }
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
    fun selectAudio(index: Int) {
        val source = _state.value?.source ?: return
        if (index == source.selectedAudio) return
        val track = source.audioTracks.firstOrNull { it.index == index } ?: return
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
        reportStopped()
        _player.value?.let {
            it.stop()
            it.clearMediaItems()
        }
    }

    private companion object {
        const val PROGRESS_REPORT_MS = 10_000L
        const val EXTERNAL_PREFIX = "ffext-"
    }
}
