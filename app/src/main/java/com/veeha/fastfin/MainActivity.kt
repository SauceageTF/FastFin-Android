package com.veeha.fastfin

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.veeha.fastfin.ui.FastFinRoot
import com.veeha.fastfin.ui.PipController
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The single activity. Compose draws every screen; this class owns what only
 * a window can do: orientation, immersive mode, the HDR colour mode, keeping
 * the screen on, and Picture in Picture.
 */
class MainActivity : ComponentActivity() {
    private val graph by lazy { appGraph }
    private var inPip by mutableStateOf(false)
    private var windowState = WindowState()

    private val pipSupported by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }

    private val pip = object : PipController {
        override val supported: Boolean get() = pipSupported
        override fun enter() {
            if (pipSupported) runCatching { enterPictureInPictureMode(pipParams(windowState)) }
        }

        override fun setSourceRect(rect: android.graphics.Rect) {
            if (rect == sourceRect) return
            sourceRect = rect
            if (pipSupported) runCatching { setPictureInPictureParams(pipParams(windowState)) }
        }
    }
    private var sourceRect: android.graphics.Rect? = null

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(EXTRA_CONTROL, 0)) {
                CONTROL_PLAY_PAUSE -> graph.playback.togglePlay()
                CONTROL_BACK -> graph.playback.seekBy(-10_000)
                CONTROL_FORWARD -> graph.playback.seekBy(10_000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        // Hold the system splash only until the saved session has been read
        // from disk (milliseconds; never a network wait), so the first frame
        // is already the right screen.
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (graph.sessions.restoring.value) return false
                content.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })

        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(ACTION_PIP_CONTROL), ContextCompat.RECEIVER_NOT_EXPORTED)

        addOnPictureInPictureModeChangedListener { info ->
            inPip = info.isInPictureInPictureMode
            // Leaving PiP while not resumed means the user closed the PiP
            // window: stop the sound and go back to the mini player.
            if (!info.isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                graph.playback.pause()
                graph.playback.collapse()
            }
        }

        setContent { FastFinRoot(graph, inPip, pip) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                combine(
                    graph.playback.state,
                    graph.playback.isPlaying,
                    graph.playback.videoAspect,
                    graph.settings.flow,
                    snapshotFlow { inPip },
                ) { ui, playing, aspect, settings, pip ->
                    WindowState(
                        active = ui != null,
                        expanded = ui?.expanded == true,
                        playing = playing,
                        hdr = ui?.source?.hdr != null,
                        aspect = aspect,
                        autoPip = settings.autoPip,
                        pip = pip,
                    )
                }.distinctUntilChanged().collect(::applyWindow)
            }
        }
    }

    private fun applyWindow(state: WindowState) {
        val previous = windowState
        windowState = state
        val fullScreen = state.expanded && !state.pip

        if (fullScreen != (previous.expanded && !previous.pip) || previous == WindowState()) {
            requestedOrientation = if (fullScreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (fullScreen) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }

        if (state.playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // HDR colour mode only while HDR video fills the screen: it lets the
        // panel run at full HDR brightness, and it costs power, so the browse
        // UI never pays for it.
        window.colorMode =
            if (state.hdr && (state.expanded || state.pip)) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT

        if (pipSupported) runCatching { setPictureInPictureParams(pipParams(state)) }
    }

    private fun pipParams(state: WindowState): PictureInPictureParams {
        val aspect = state.aspect.coerceIn(1f / 2.39f, 2.39f)
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational((aspect * 1000).roundToInt(), 1000))
            .setActions(
                listOf(
                    action(R.drawable.ic_pip_back, "Back 10 seconds", CONTROL_BACK),
                    if (state.playing) action(R.drawable.ic_pip_pause, "Pause", CONTROL_PLAY_PAUSE)
                    else action(R.drawable.ic_pip_play, "Play", CONTROL_PLAY_PAUSE),
                    action(R.drawable.ic_pip_forward, "Forward 10 seconds", CONTROL_FORWARD),
                )
            )
        sourceRect?.takeIf { !it.isEmpty }?.let(builder::setSourceRectHint)
        if (Build.VERSION.SDK_INT >= 31) {
            // Swiping home while a video plays glides it into PiP (Android 12+).
            builder.setAutoEnterEnabled(state.autoPip && state.active && state.playing)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun action(icon: Int, title: String, control: Int): RemoteAction {
        val intent = Intent(ACTION_PIP_CONTROL).setPackage(packageName).putExtra(EXTRA_CONTROL, control)
        val pending = PendingIntent.getBroadcast(this, control, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return RemoteAction(Icon.createWithResource(this, icon), title, title, pending)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Before Android 12 there is no auto-enter; do it by hand.
        val state = windowState
        if (Build.VERSION.SDK_INT < 31 && pipSupported && state.autoPip && state.active && state.playing) pip.enter()
    }

    override fun onStop() {
        super.onStop()
        // Video does not play to an empty room: backgrounded without PiP means pause.
        if (!isInPictureInPictureMode) graph.playback.pause()
    }

    override fun onDestroy() {
        unregisterReceiver(pipReceiver)
        super.onDestroy()
    }

    private data class WindowState(
        val active: Boolean = false,
        val expanded: Boolean = false,
        val playing: Boolean = false,
        val hdr: Boolean = false,
        val aspect: Float = 16f / 9f,
        val autoPip: Boolean = true,
        val pip: Boolean = false,
    )

    private companion object {
        const val ACTION_PIP_CONTROL = "com.veeha.fastfin.PIP_CONTROL"
        const val EXTRA_CONTROL = "control"
        const val CONTROL_PLAY_PAUSE = 1
        const val CONTROL_BACK = 2
        const val CONTROL_FORWARD = 3
    }
}
