package forage.android

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import forage.app.ui.VideoSurface
import forage.core.source.Playback

/**
 * Forage's own player on Android: ExoPlayer for streams (YouTube's video and audio, played
 * together), a web view for pages made for embedding (Pornhub's), with the site's own controls.
 *
 * One of each at a time, kept here rather than in the screen, so the page and full screen show the
 * same player: it carries on where it was. Once nothing shows it for a moment, it's let go.
 */
@OptIn(UnstableApi::class)
class AndroidVideoSurface(private val activity: Activity) : VideoSurface {
    private var exo: ExoPlayer? = null
    private var exoPlays: Playback? = null
    private var web: WebView? = null
    private var webPlays: Playback? = null

    /** The embedded page's own full-screen view, while it shows one. */
    private var pageFullscreen by mutableStateOf<View?>(null)
    private var pageFullscreenCallback: WebChromeClient.CustomViewCallback? = null

    // What the screen showing the player wants told; the newest screen's.
    private var problem: (String) -> Unit = {}
    private var ended: () -> Unit = {}
    private var wantsFullscreen: (Boolean) -> Unit = {}

    private var shownBy = 0
    private val main = Handler(Looper.getMainLooper())
    private val letGo = Runnable { if (shownBy == 0) release() }

    @Composable
    override fun Player(
        playback: Playback,
        fullscreen: Boolean,
        onFullscreen: (Boolean) -> Unit,
        onProblem: (String) -> Unit,
        onEnded: () -> Unit,
        modifier: Modifier,
    ) {
        val currentProblem by rememberUpdatedState(onProblem)
        val currentEnded by rememberUpdatedState(onEnded)
        val currentFullscreen by rememberUpdatedState(onFullscreen)
        problem = { currentProblem(it) }
        ended = { currentEnded() }
        wantsFullscreen = { currentFullscreen(it) }
        DisposableEffect(Unit) {
            shownBy++
            main.removeCallbacks(letGo)
            // Going full screen swaps one screen for another: wait a moment before letting go.
            onDispose {
                shownBy--
                main.postDelayed(letGo, 500)
            }
        }
        when {
            playback.videoUrl != null -> Stream(playback, fullscreen, modifier)
            playback.embedUrl != null -> Embedded(playback, fullscreen, modifier)
            else -> DisposableEffect(playback) {
                problem("Nothing to play")
                onDispose {}
            }
        }
    }

    @Composable
    private fun Stream(playback: Playback, fullscreen: Boolean, modifier: Modifier) {
        val player = streamPlayer(playback)
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    keepScreenOn = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                }
            },
            update = { view ->
                if (view.player !== player) view.player = player
                view.setFullscreenButtonState(fullscreen)
                view.setFullscreenButtonClickListener { wantsFullscreen(it) }
            },
            onRelease = { it.player = null },
            modifier = modifier,
        )
    }

    private fun streamPlayer(playback: Playback): ExoPlayer {
        exo?.let { if (exoPlays == playback) return it }
        // Another audio track for the same video: it carries on from where it was.
        val from = exo?.takeIf { exoPlays?.videoUrl == playback.videoUrl }?.let { it.currentPosition to it.playWhenReady }
        releaseStream()
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(playback.headers - "User-Agent")
        playback.headers["User-Agent"]?.let(http::setUserAgent)
        val streams = ProgressiveMediaSource.Factory(http)
        val video = streams.createMediaSource(MediaItem.fromUri(playback.videoUrl!!))
        val source = playback.audioUrl?.let { MergingMediaSource(video, streams.createMediaSource(MediaItem.fromUri(it))) } ?: video
        val player = ExoPlayer.Builder(activity).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    problem("The stream didn't play (${error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')})")
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) ended()
                }
            })
            if (from != null) setMediaSource(source, from.first) else setMediaSource(source)
            prepare()
            playWhenReady = from?.second ?: true
        }
        exo = player
        exoPlays = playback
        return player
    }

    @Composable
    private fun Embedded(playback: Playback, fullscreen: Boolean, modifier: Modifier) {
        val view = embeddedPage(playback)
        AndroidView({ FrameLayout(it).apply { setBackgroundColor(Color.BLACK) } }, modifier, update = { it.holdOnly(view) }, onRelease = { it.removeAllViews() })
    }

    /** True while the embedded player's own full screen (its button) covers the window. */
    val inPageFullscreen: Boolean get() = pageFullscreen != null

    /** Leaves the embedded player's own full screen (Back, or the phone turned upright). */
    fun leavePageFullscreen() {
        val callback = pageFullscreenCallback
        hidePageFullscreen()
        callback?.onCustomViewHidden()
    }

    /**
     * The page's full screen goes over the whole window, on top of Forage, with the web view left
     * where it is: taking the web view away would end the page's full screen at once.
     */
    private fun showPageFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        hidePageFullscreen()
        view.setBackgroundColor(Color.BLACK)
        (activity.window.decorView as ViewGroup).addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pageFullscreen = view
        pageFullscreenCallback = callback
    }

    private fun hidePageFullscreen() {
        pageFullscreen?.let { (it.parent as? ViewGroup)?.removeView(it) }
        pageFullscreen = null
        pageFullscreenCallback = null
    }

    /** Puts [child] in this frame alone, taking it from wherever it was. */
    private fun FrameLayout.holdOnly(child: View) {
        if (child.parent === this) return
        (child.parent as? ViewGroup)?.removeView(child)
        removeAllViews()
        addView(child, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun embeddedPage(playback: Playback): WebView {
        web?.let { if (webPlays == playback) return it }
        releaseEmbedded()
        val url = playback.embedUrl!!
        val embed = android.net.Uri.parse(url)
        val view = WebView(activity).apply {
            keepScreenOn = true
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            // The page is there to play the video and nothing else: no pop-ups (a window it asks
            // for is never made), and it stays on the embed (a tap on the paused video would
            // otherwise open the site's own page for it, ads and all).
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    request.isForMainFrame && !(request.url.host == embed.host && request.url.path == embed.path)

                // Starts the video itself, so one tap on Play is enough.
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript(AUTOPLAY, null)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message) = false

                override fun onShowCustomView(view: View, callback: CustomViewCallback) = showPageFullscreen(view, callback)

                override fun onHideCustomView() = hidePageFullscreen()
            }
            loadUrl(url)
        }
        web = view
        webPlays = playback
        return view
    }

    /** Forage went to the background: the video waits. */
    fun pause() {
        exo?.pause()
        web?.onPause()
    }

    fun resume() {
        web?.onResume()
    }

    override fun release() {
        main.removeCallbacks(letGo)
        releaseStream()
        releaseEmbedded()
    }

    private fun releaseStream() {
        exo?.release()
        exo = null
        exoPlays = null
    }

    private fun releaseEmbedded() {
        leavePageFullscreen()
        web?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.loadUrl("about:blank")
            view.destroy()
        }
        web = null
        webPlays = null
    }

    private companion object {
        /** Plays the page's video as soon as it has one, for up to 15 seconds after the page loads. */
        const val AUTOPLAY = """(function () {
            var tries = 0;
            var timer = setInterval(function () {
                var video = document.querySelector('video');
                if (video && !video.paused) { clearInterval(timer); return; }
                if (video) { var started = video.play(); if (started && started.catch) started.catch(function () {}); }
                if (++tries > 30) clearInterval(timer);
            }, 500);
        })();"""
    }
}
