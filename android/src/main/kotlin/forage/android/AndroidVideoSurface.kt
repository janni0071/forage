package forage.android

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
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
    private var report: (Long, Long) -> Unit = { _, _ -> }
    private var startAt: Long? = null
    private var expected: Long? = null

    // Where each player says how far it got: the screen's that started it, so a player let go
    // after the next video started still reports its own video.
    private var streamReport: (Long, Long) -> Unit = { _, _ -> }
    private var embeddedReport: (Long, Long) -> Unit = { _, _ -> }

    /**
     * Where the embedded video is, once Forage has taken over its controls (the page then shows
     * the video alone); null while the page's own player is in charge (starting, an ad).
     */
    private var embedState by mutableStateOf<EmbedState?>(null)

    /** Every few seconds while there's a player: how far it is. */
    private val tick = object : Runnable {
        override fun run() {
            reportPositions()
            if (exo != null || web != null) main.postDelayed(this, POSITION_EVERY_MS)
        }
    }

    private var shownBy = 0
    private val main = Handler(Looper.getMainLooper())
    private val letGo = Runnable { if (shownBy == 0) release() }

    @Composable
    override fun Player(
        playback: Playback,
        fullscreen: Boolean,
        startAt: Long?,
        expectedSeconds: Long?,
        onFullscreen: (Boolean) -> Unit,
        onProblem: (String) -> Unit,
        onEnded: () -> Unit,
        onPosition: (seconds: Long, duration: Long) -> Unit,
        modifier: Modifier,
    ) {
        val currentProblem by rememberUpdatedState(onProblem)
        val currentEnded by rememberUpdatedState(onEnded)
        val currentFullscreen by rememberUpdatedState(onFullscreen)
        val currentPosition by rememberUpdatedState(onPosition)
        problem = { currentProblem(it) }
        ended = { currentEnded() }
        wantsFullscreen = { currentFullscreen(it) }
        report = { seconds, duration -> currentPosition(seconds, duration) }
        this.startAt = startAt
        expected = expectedSeconds
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
        // Another audio track for the same video: it carries on from where it was. A new video
        // starts where it was left off, if it was.
        val from = exo?.takeIf { exoPlays?.videoUrl == playback.videoUrl }?.let { it.currentPosition to it.playWhenReady }
            ?: startAt?.let { it * 1000 to true }
        releaseStream()
        streamReport = report
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
                    if (state == Player.STATE_ENDED) {
                        reportStream()
                        ended()
                    }
                }

                // Paused: worth noting where, in case Forage is closed next.
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!isPlaying) reportStream()
                }
            })
            if (from != null) setMediaSource(source, from.first) else setMediaSource(source)
            prepare()
            playWhenReady = from?.second ?: true
        }
        exo = player
        exoPlays = playback
        startTicking()
        return player
    }

    private fun startTicking() {
        main.removeCallbacks(tick)
        main.postDelayed(tick, POSITION_EVERY_MS)
    }

    private fun reportPositions() {
        if (exo?.isPlaying == true) reportStream()
        reportEmbedded()
    }

    private fun reportStream() {
        val player = exo ?: return
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        streamReport(player.currentPosition / 1000, duration / 1000)
    }

    private fun reportEmbedded() {
        val view = web ?: return
        val reportTo = embeddedReport
        view.evaluateJavascript(VIDEO_POSITION) { answer ->
            // "12.3,845.1", or an empty answer before there's a video.
            val parts = answer.trim('"').split(',').mapNotNull { it.toDoubleOrNull() }
            if (parts.size == 2 && parts[1].isFinite() && parts[1] > 0) reportTo(parts[0].toLong(), parts[1].toLong())
        }
    }

    @Composable
    private fun Embedded(playback: Playback, fullscreen: Boolean, modifier: Modifier) {
        val view = embeddedPage(playback)
        Box(modifier) {
            AndroidView({ FrameLayout(it).apply { setBackgroundColor(Color.BLACK) } }, Modifier.fillMaxSize(), update = { it.holdOnly(view) }, onRelease = { it.removeAllViews() })
            embedState?.let { state ->
                EmbedControls(
                    state, fullscreen,
                    onPlayPause = { view.evaluateJavascript(PLAY_PAUSE, null) },
                    onSeek = { seconds -> view.evaluateJavascript("(function(){var v=document.querySelector('video'); if (v) v.currentTime = $seconds;})()", null) },
                    onFullscreen = { wantsFullscreen(it) },
                )
            }
        }
        // Out of full screen some other way (Back, the phone upright): the page's player is told.
        LaunchedEffect(fullscreen) { if (!fullscreen) view.evaluateJavascript("window.__forageLeft && window.__forageLeft()", null) }
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
        embeddedReport = report
        val resumeAt = startAt
        val length = expected
        val view = WebView(activity).apply {
            keepScreenOn = true
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            // The page is there to play the video and nothing else: it stays on the embed (a tap on
            // the paused video would otherwise open the site's own page for it, ads and all). A
            // window it opens would load in its place, and is stopped the same way.
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    request.isForMainFrame && !(request.url.host == embed.host && request.url.path == embed.path)

                // Presses the player's own Play, so one tap on Forage's is enough, then goes to
                // where it was left off.
                override fun onPageFinished(view: WebView, url: String) {
                    if (url == "about:blank") return
                    if (!startScriptSupported) view.evaluateJavascript(FULLSCREEN, null)
                    main.postDelayed({ tapToStart(view, 0) }, 1200)
                    main.postDelayed({ takeOver(view, length, 0) }, 1500)
                    if (resumeAt != null) main.postDelayed({ resumeEmbedded(view, resumeAt, length, 0) }, 1500)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) = showPageFullscreen(view, callback)

                override fun onHideCustomView() = hidePageFullscreen()
            }
            // The player's full-screen button asks the browser for full screen, which a web view
            // inside an app can't give well; the script hands the request to Forage's own.
            addJavascriptInterface(FullscreenBridge(), "ForageFullscreen")
            addJavascriptInterface(VideoBridge(), "ForageVideo")
            if (startScriptSupported) WebViewCompat.addDocumentStartJavaScript(this, FULLSCREEN, setOf("*"))
            loadUrl(url)
        }
        web = view
        webPlays = playback
        startTicking()
        return view
    }

    private val startScriptSupported get() = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    /** What the page's video reports once Forage has its controls: where it is, and whether it plays. */
    private inner class VideoBridge {
        @JavascriptInterface
        fun state(seconds: Double, duration: Double, paused: Boolean) {
            main.post { if (web != null) embedState = EmbedState(seconds, duration, paused) }
        }
    }

    /**
     * Once the page's video plays and is the video (not an ad before it), the page shows the video
     * alone and Forage's controls go over it: the site's are made for a mouse, always showing, and
     * a tap pauses. Tries every second for two minutes.
     */
    private fun takeOver(view: WebView, length: Long?, attempt: Int) {
        if (web !== view || attempt >= 120) return
        view.evaluateJavascript("($TAKE_OVER)(${length ?: 0})") { answer ->
            if (web === view && "done" !in answer) main.postDelayed({ takeOver(view, length, attempt + 1) }, 1000)
        }
    }

    /** What the page's script calls when its player goes full screen or leaves it. */
    private inner class FullscreenBridge {
        @JavascriptInterface
        fun change(on: Boolean) {
            main.post { wantsFullscreen(on) }
        }
    }

    /**
     * Goes to [seconds] in once the page's video plays and is the video (as long as it should be,
     * not an ad before it): tries every second for a minute and a half.
     */
    private fun resumeEmbedded(view: WebView, seconds: Long, length: Long?, attempt: Int) {
        if (web !== view || attempt >= 90) return
        view.evaluateJavascript("($SEEK_WHEN_READY)($seconds, ${length ?: 0})") { answer ->
            if (web === view && "done" !in answer) main.postDelayed({ resumeEmbedded(view, seconds, length, attempt + 1) }, 1000)
        }
    }

    /**
     * Taps the middle of the embedded player until its video plays (a few times at most). A real
     * tap, not a script calling play(): the player loads the video only once its own Play is
     * pressed, and a video started behind its back waits for data that never comes.
     */
    private fun tapToStart(view: WebView, attempt: Int) {
        if (web !== view || attempt >= 4) return
        view.evaluateJavascript(VIDEO_STATE) { state ->
            // Playing, or loading after Play (a video that was asked to play isn't paused): done.
            if (web !== view || "playing" in state) return@evaluateJavascript
            if (view.width > 0 && view.height > 0 && view.isAttachedToWindow) {
                val x = view.width / 2f
                val y = view.height / 2f
                val down = android.os.SystemClock.uptimeMillis()
                MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0).also { view.dispatchTouchEvent(it); it.recycle() }
                MotionEvent.obtain(down, down + 60, MotionEvent.ACTION_UP, x, y, 0).also { view.dispatchTouchEvent(it); it.recycle() }
            }
            main.postDelayed({ tapToStart(view, attempt + 1) }, 2000)
        }
    }

    /** Forage went to the background: the video waits, and where it is is noted. */
    fun pause() {
        exo?.pause()
        reportEmbedded()
        web?.onPause()
    }

    fun resume() {
        web?.onResume()
    }

    override fun release() {
        main.removeCallbacks(letGo)
        main.removeCallbacks(tick)
        releaseStream()
        releaseEmbedded()
    }

    private fun releaseStream() {
        reportStream()
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
        embedState = null
    }

    private companion object {
        /** Whether the page's video plays: "playing", "paused", or "none" before the player made one. */
        const val VIDEO_STATE = """(function () {
            var video = document.querySelector('video');
            return !video ? 'none' : video.paused ? 'paused' : 'playing';
        })()"""

        /** Where the page's video is, as "seconds,duration"; empty before there's a video. */
        const val VIDEO_POSITION = """(function () {
            var video = document.querySelector('video');
            return video && isFinite(video.duration) ? video.currentTime + ',' + video.duration : '';
        })()"""

        /** Goes to a moment in the page's video once it plays and is as long as the video should be. */
        const val SEEK_WHEN_READY = """function (seconds, length) {
            var video = document.querySelector('video');
            if (!video || video.paused || !isFinite(video.duration) || video.duration <= 0) return 'wait';
            if (length > 0 && Math.abs(video.duration - length) > Math.max(15, length / 20)) return 'wait';
            video.currentTime = seconds;
            return 'done';
        }"""

        /**
         * Hands the page's full-screen requests to Forage: the player believes it's full screen
         * (the element, the events), Forage fills the screen with the web view.
         */
        const val FULLSCREEN = """(function () {
            if (window.__forageFullscreen || !window.ForageFullscreen) return;
            window.__forageFullscreen = true;
            var current = null;
            function changed(element) {
                ['fullscreenchange', 'webkitfullscreenchange'].forEach(function (name) {
                    (element || document).dispatchEvent(new Event(name, { bubbles: true }));
                });
            }
            function enter() {
                current = this;
                ForageFullscreen.change(true);
                changed(current);
                return Promise.resolve();
            }
            function leave() {
                var was = current;
                current = null;
                ForageFullscreen.change(false);
                if (was) changed(was);
                return Promise.resolve();
            }
            ['requestFullscreen', 'webkitRequestFullscreen', 'webkitRequestFullScreen'].forEach(function (name) {
                Element.prototype[name] = enter;
            });
            if (window.HTMLVideoElement) HTMLVideoElement.prototype.webkitEnterFullscreen = enter;
            ['exitFullscreen', 'webkitExitFullscreen', 'webkitCancelFullScreen'].forEach(function (name) {
                Document.prototype[name] = leave;
            });
            ['fullscreenElement', 'webkitFullscreenElement', 'webkitCurrentFullScreenElement'].forEach(function (name) {
                Object.defineProperty(Document.prototype, name, { configurable: true, get: function () { return current; } });
            });
            ['fullscreenEnabled', 'webkitFullscreenEnabled'].forEach(function (name) {
                Object.defineProperty(Document.prototype, name, { configurable: true, get: function () { return true; } });
            });
            window.__forageLeft = function () {
                var was = current;
                current = null;
                if (was) changed(was);
            };
        })();"""

        /**
         * Hides everything on the page but its video, and has the video report to Forage, once it
         * plays and is as long as the video should be (at least half a minute when that's unknown).
         */
        const val TAKE_OVER = """function (length) {
            var video = document.querySelector('video');
            if (!video || video.paused || !isFinite(video.duration) || video.duration <= 0) return 'wait';
            if (length > 0 ? Math.abs(video.duration - length) > Math.max(15, length / 20) : video.duration < 30) return 'wait';
            if (!document.getElementById('forage-video-only')) {
                var style = document.createElement('style');
                style.id = 'forage-video-only';
                style.textContent = '* { visibility: hidden !important; } video { visibility: visible !important; }';
                (document.head || document.documentElement).appendChild(style);
            }
            if (!video.__forage) {
                video.__forage = true;
                var send = function () { ForageVideo.state(video.currentTime, video.duration, video.paused); };
                ['timeupdate', 'play', 'pause', 'seeked', 'durationchange', 'ended'].forEach(function (name) {
                    video.addEventListener(name, send);
                });
                send();
            }
            return 'done';
        }"""

        const val PLAY_PAUSE = """(function () {
            var video = document.querySelector('video');
            if (video) { if (video.paused) video.play(); else video.pause(); }
        })()"""

        const val POSITION_EVERY_MS = 5000L
    }
}
