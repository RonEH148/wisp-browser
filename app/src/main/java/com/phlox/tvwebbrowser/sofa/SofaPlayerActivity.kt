package com.phlox.tvwebbrowser.sofa

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.view.MotionEvent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import android.app.AlertDialog
import android.app.Activity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Native full-screen player for web videos. It plays through Media3/ExoPlayer on a SurfaceView, so
 * the TV's hardware decoder renders 4K, HDR10, HLG and Dolby Vision directly (web engines usually
 * can't), sends Dolby/DTS audio to the TV or soundbar when supported, and can switch the TV's
 * refresh rate to match the video.
 */
@OptIn(UnstableApi::class)
class SofaPlayerActivity : Activity() {
    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_USER_AGENT = "user_agent"
        const val EXTRA_COOKIES = "cookies"
        const val EXTRA_POSITION_MS = "position_ms"
        const val EXTRA_DEBAND = "deband"
        const val EXTRA_HEADERS = "headers"
        const val EXTRA_SUBS = "subtitles"
        private val SUBTITLE_SIZES = floatArrayOf(0.042f, 0.0533f, 0.068f, 0.085f)
        private const val SEEK_STEP_MS = 10_000L

        const val EXTRA_PAGE_FETCH = "page_fetch"
        const val EXTRA_LINES = "lines"

        fun start(context: Context, stream: SofaVideoHandoff.Stream, deband: Boolean = false,
                  pageFetch: Boolean = false, lines: Boolean = false) {
            val intent = Intent(context, SofaPlayerActivity::class.java)
                .putExtra(EXTRA_URL, stream.url)
                .putExtra(EXTRA_TITLE, stream.title)
                .putExtra(EXTRA_REFERER, stream.referer)
                .putExtra(EXTRA_USER_AGENT, stream.userAgent)
                .putExtra(EXTRA_COOKIES, stream.cookies)
                .putExtra(EXTRA_POSITION_MS, stream.positionMs)
                .putExtra(EXTRA_DEBAND, deband)
                .putExtra(EXTRA_HEADERS, HashMap(stream.headers))
                .putStringArrayListExtra(EXTRA_SUBS, ArrayList(stream.subtitles))
                .putExtra(EXTRA_PAGE_FETCH, pageFetch)
                .putExtra(EXTRA_LINES, lines)
            context.startActivity(intent)
        }
    }

    private val config = AppContext.provideConfig()
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var infoView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var refreshRateApplied = false
    private var stream: SofaVideoHandoff.Stream? = null

    //Deband: per video, off when the player opens
    private lateinit var debandButton: TextView
    private var cleanup = LumenVideoCleanup.Level.OFF
    private val debandOn get() = cleanup != LumenVideoCleanup.Level.OFF
    private var effectsPipelineReady = false
    private var trackSelector: DefaultTrackSelector? = null

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }
        stream = SofaVideoHandoff.Stream(
            url = url,
            title = intent.getStringExtra(EXTRA_TITLE) ?: "",
            referer = intent.getStringExtra(EXTRA_REFERER),
            userAgent = intent.getStringExtra(EXTRA_USER_AGENT),
            cookies = intent.getStringExtra(EXTRA_COOKIES),
            headers = readHeaders(),
            positionMs = intent.getLongExtra(EXTRA_POSITION_MS, 0L),
            subtitles = intent.getStringArrayListExtra(EXTRA_SUBS).orEmpty()
        )

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        //TVs: the hardware video layer by default (least work for the TV's chip, needed for HDR);
        //tablets/phones: GPU drawing by default. The Display button changes it and is remembered.
        isTv = (getSystemService(UI_MODE_SERVICE) as android.app.UiModeManager).currentModeType ==
                android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        smoothDisplay = getSharedPreferences("lumen_player", MODE_PRIVATE).getBoolean("smooth_display", !isTv)
        playerView = if (smoothDisplay) {
            layoutInflater.inflate(R.layout.lumen_player_texture, root, false) as PlayerView
        } else PlayerView(this)
        playerView.keepScreenOn = true
        playerView.setShowSubtitleButton(true)
        playerView.setShowNextButton(false)
        playerView.setShowPreviousButton(false)
        playerView.setControllerShowTimeoutMs(4000)
        applySubtitleStyle()
        setupTouchGestures()
        root.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        infoView = TextView(this)
        infoView.setTextColor(Color.WHITE)
        infoView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        infoView.setBackgroundColor(0xB0000000.toInt())
        val pad = (12 * resources.displayMetrics.density).roundToInt()
        infoView.setPadding(pad, pad / 2, pad, pad / 2)
        infoView.visibility = View.GONE
        root.addView(infoView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).also { it.setMargins(pad * 3, pad * 3, pad * 3, pad * 3) })

        //small "Deband" toggle, shown together with the player controls
        debandButton = TextView(this)
        debandButton.setTextColor(Color.WHITE)
        debandButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        debandButton.setPadding(pad + pad / 2, pad / 2 + 2, pad + pad / 2, pad / 2 + 2)
        debandButton.isFocusable = true
        debandButton.isClickable = true
        debandButton.visibility = View.GONE
        debandButton.setOnClickListener { toggleDeband() }
        debandButton.setOnFocusChangeListener { _, _ -> updateDebandButton() }
        updateDebandButton()
        //second button: how the picture reaches the screen
        displayButton = TextView(this)
        displayButton.setTextColor(Color.WHITE)
        displayButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        displayButton.setPadding(pad + pad / 2, pad / 2 + 2, pad + pad / 2, pad / 2 + 2)
        displayButton.isFocusable = true
        displayButton.isClickable = true
        displayButton.setOnClickListener { switchDisplayMode() }
        displayButton.setOnFocusChangeListener { _, _ -> updateDisplayButton() }
        updateDisplayButton()

        topBar = android.widget.LinearLayout(this)
        topBar.orientation = android.widget.LinearLayout.HORIZONTAL
        topBar.visibility = View.GONE
        //deinterlacer (line remover) on/off, first in the bar
        linesButton = makeBarButton(pad) { toggleLines() }
        linesButton.setOnFocusChangeListener { _, _ -> updateLinesButton() }
        updateLinesButton()
        topBar.addView(linesButton)
        topBar.addView(debandButton)
        topBar.addView(displayButton, android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.marginStart = pad })
        //subtitle size, and "start over" after a video continued where it was left
        subsButton = makeBarButton(pad) { cycleSubtitleSize() }
        updateSubsButton()
        topBar.addView(subsButton, android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.marginStart = pad })
        startOverButton = makeBarButton(pad) {
            player?.seekTo(0)
            startOverButton.visibility = View.GONE
            playerView.requestFocus()
        }
        startOverButton.text = getString(R.string.lumen_start_over)
        startOverButton.visibility = View.GONE
        topBar.addView(startOverButton, android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.marginStart = pad })
        debandButton.visibility = View.GONE //Lumen: deband removed (use an external player's own)
        root.addView(topBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).also { it.setMargins(pad * 3, pad * 3, pad * 3, pad * 3) })
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            topBar.visibility = if (visibility == View.VISIBLE) View.VISIBLE else View.GONE
            debandTicker.removeCallbacks(debandTick)
            if (visibility == View.VISIBLE) debandTicker.post(debandTick)
        })

        setContentView(root)
        initPlayer(stream!!)
    }

    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    private fun readHeaders(): Map<String, String> = try {
        (intent.getSerializableExtra(EXTRA_HEADERS) as? HashMap<String, String>) ?: emptyMap()
    } catch (e: Exception) {
        emptyMap()
    }

    /** The headers every browser sends with a video request; some video servers refuse requests without them. */
    private fun addBrowserHeaders(headers: MutableMap<String, String>, url: String) {
        fun putIfMissing(k: String, v: String) {
            if (headers.keys.none { it.equals(k, true) }) headers[k] = v
        }
        putIfMissing("Accept", "*/*")
        putIfMissing("Accept-Language", java.util.Locale.getDefault().toLanguageTag() + ",en;q=0.8")
        val origin = headers.entries.firstOrNull { it.key.equals("Origin", true) }?.value
            ?: headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value
        val site = try {
            val a = android.net.Uri.parse(origin ?: "").host
            val b = android.net.Uri.parse(url).host
            fun base(h: String?) = h?.split('.')?.takeLast(2)?.joinToString(".")
            when {
                a == null -> "none"
                a == b -> "same-origin"
                base(a) == base(b) -> "same-site"
                else -> "cross-site"
            }
        } catch (e: Exception) { "cross-site" }
        putIfMissing("Sec-Fetch-Dest", "empty")
        putIfMissing("Sec-Fetch-Mode", "cors")
        putIfMissing("Sec-Fetch-Site", site)
    }

    /**
     * Android's own copy of Chrome's network stack (HttpEngine, Android 14+ and updated Android 12/13).
     * Many video servers (e.g. behind Cloudflare) recognise a browser by how its secure connection
     * looks and refuse other players with HTTP 403; requests made this way look like Chrome's.
     * Returns null when not available (then the standard Android HTTP client is used).
     */
    @android.annotation.SuppressLint("NewApi", "WrongConstant")
    private fun browserLikeHttp(headers: Map<String, String>, userAgent: String?): androidx.media3.datasource.HttpDataSource.Factory? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            if (android.os.ext.SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) < 7) return null
            val builder = android.net.http.HttpEngine.Builder(this)
                .setEnableHttp2(true)
                .setEnableQuic(true)
                .setEnableBrotli(true)
            userAgent?.let { builder.setUserAgent(it) }
            val engine = builder.build()
            androidx.media3.datasource.HttpEngineDataSource.Factory(engine, java.util.concurrent.Executors.newSingleThreadExecutor())
                .setDefaultRequestProperties(headers)
                .setUserAgent(userAgent)
                .setConnectionTimeoutMs(20000)
                .setReadTimeoutMs(20000)
        } catch (e: Throwable) {
            null
        }
    }

    /** Chrome's network stack from Google Play services (Lumen only; null in Wisp), see [CronetSupport]. */
    private fun playServicesHttp(headers: Map<String, String>, userAgent: String?): androidx.media3.datasource.HttpDataSource.Factory? =
        CronetSupport.playerFactory(this, headers, userAgent)

    /** Which network stack the player uses (shown with errors). */
    private var netStack = "basic"

    private fun initPlayer(stream: SofaVideoHandoff.Stream) {
        val headers = HashMap<String, String>()
        headers.putAll(stream.headers)
        if (headers.keys.none { it.equals("Referer", true) }) stream.referer?.let { headers["Referer"] = it }
        stream.cookies?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
        addBrowserHeaders(headers, stream.url)
        val pageFetch = intent.getBooleanExtra(EXTRA_PAGE_FETCH, false)
        //downloaded by the page: the player only talks to Lumen's relay on this device
        val chromeLike = if (pageFetch) DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20000)
            .setReadTimeoutMs(60000)
            .also { netStack = "downloaded by the page" }
            else browserLikeHttp(headers, stream.userAgent)?.also { netStack = "Android HttpEngine" }
            ?: playServicesHttp(headers, stream.userAgent)?.also { netStack = "Play services Cronet" }
        val dataSourceFactory = DefaultDataSource.Factory(this, chromeLike
            ?: DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(headers)
                .setConnectTimeoutMs(20000)
                .setReadTimeoutMs(20000)
                .also { f -> stream.userAgent?.let { f.setUserAgent(it) } })

        val trackSelector = DefaultTrackSelector(this)
        this.trackSelector = trackSelector
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setForceHighestSupportedBitrate(config.sofaAutoMaxQuality)
                .setTunnelingEnabled(config.sofaPlayerTunneling)
                //subtitles: the device language first, otherwise the page's (unnamed) subtitle file
                .setPreferredTextLanguage(java.util.Locale.getDefault().language)
                .setSelectUndeterminedTextLanguage(true)
        )

        //Media3 uses the device's hardware decoders (MediaCodec) first, exactly like VLC/MX/Just Player,
        //and only falls back to another decoder if the first one fails
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        //Bigger buffer for high-bitrate streams (4K HLS): fewer stalls. The buffer lives in the app's
        //Java memory, so it's capped to a third of what this device gives the app - a fixed large
        //buffer made 10+ Mbit/s videos run out of memory on TVs.
        val maxHeap = Runtime.getRuntime().maxMemory()
        val bufferBytes = (maxHeap / 3).coerceIn(32L * 1024 * 1024, 160L * 1024 * 1024).toInt()
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 90_000, 2_500, 5_000)
            .setTargetBufferBytes(bufferBytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(10_000, true)
            .build()

        val exo = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(), true
            )
            .setHandleAudioBecomingNoisy(true)
            .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            .build()

        exo.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long
            ) {
                decoderLabel = (if (isHardwareDecoder(decoderName)) "HW decoder" else "Software decoder") + " ($decoderName)"
                exo.videoFormat?.let { showInfo(it) }
            }

            override fun onDroppedVideoFrames(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                droppedFrames: Int, elapsedMs: Long
            ) {
                onFramesDropped(droppedFrames)
            }
        })
        exo.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (debandOn && isHdr(exo.videoFormat)) setCleanup(LumenVideoCleanup.Level.OFF, announce = false)
                applySurfaceSize()
                updateInfoAndDisplayMode()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    updateInfoAndDisplayMode()
                    showResumedNotice()
                }
                if (playbackState == Player.STATE_ENDED) stream?.let { LumenResume.remove(this@SofaPlayerActivity, resumeKey(it)) }
                if (playbackState == Player.STATE_ENDED) finish()
            }

            override fun onPlayerError(error: PlaybackException) {
                //only a failure of the GPU filters is a deband problem; network/stream errors are not
                val filterFailed = error.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED
                if (linesOn && filterFailed) {
                    //the GPU couldn't run the line remover: play without it
                    linesOn = false
                    updateLinesButton()
                    val pos = exo.currentPosition
                    exo.setVideoEffects(emptyList())
                    exo.seekTo(pos)
                    exo.prepare()
                    Toast.makeText(this@SofaPlayerActivity, "The TV couldn't run the deinterlacer - playing without it",
                        Toast.LENGTH_LONG).show()
                    return
                }
                if (debandOn && filterFailed) {
                    //the GPU couldn't run the filters: turn them off and keep playing
                    cleanup = LumenVideoCleanup.Level.OFF
                    updateDebandButton()
                    val pos = exo.currentPosition
                    exo.setVideoEffects(emptyList())
                    exo.seekTo(pos)
                    exo.prepare()
                    Toast.makeText(this@SofaPlayerActivity, R.string.lumen_deband_failed, Toast.LENGTH_LONG).show()
                    return
                }
                showErrorDialog(error)
            }
        })

        //opened with "Play in Lumen player with anime deband": filters on from the first frame
        if (false && intent.getBooleanExtra(EXTRA_DEBAND, false)) { //deband removed
            cleanup = defaultOnLevel
            framesAtStart = LumenVideoCleanup.framesFiltered
            effectsPipelineReady = true
            trackSelector.setParameters(trackSelector.buildUponParameters().setTunnelingEnabled(false))
            exo.setVideoEffects(LumenVideoCleanup.effectsFor(cleanup))
            updateDebandButton()
        }

        //remove interlacing lines from the first frame (Site fixes → deinterlacing on for this site)
        if (intent.getBooleanExtra(EXTRA_LINES, false)) {
            linesOn = true
            effectsPipelineReady = true
            trackSelector.setParameters(trackSelector.buildUponParameters().setTunnelingEnabled(false))
            exo.setVideoEffects(LumenVideoCleanup.linesOnly())
            updateLinesButton()
        }

        val itemBuilder = MediaItem.Builder().setUri(stream.url)
        SofaVideoHandoff.guessMimeType(stream.url)?.let { itemBuilder.setMimeType(it) }
        val subs = subtitleConfigs(stream.subtitles)
        if (subs.isNotEmpty()) itemBuilder.setSubtitleConfigurations(subs)
        //continue where this video was left last time
        var start = stream.positionMs.coerceAtLeast(0L)
        if (start == 0L) {
            val saved = LumenResume.get(this, resumeKey(stream))
            if (saved > 0) {
                start = saved
                resumedAt = saved
            }
        }
        exo.setMediaItem(itemBuilder.build(), start)
        exo.playWhenReady = true
        exo.prepare()
        playerView.player = exo
        player = exo
    }

    private fun updateInfoAndDisplayMode() {
        val exo = player ?: return
        val format = exo.videoFormat ?: return
        if (config.sofaMatchFrameRate && !refreshRateApplied && format.frameRate > 0) {
            refreshRateApplied = true
            applyBestDisplayMode(format)
        }
        showInfo(format)
    }

    @SuppressLint("SetTextI18n")
    private fun showInfo(format: Format) {
        val parts = ArrayList<String>()
        if (format.width > 0 && format.height > 0) parts.add("${format.width}×${format.height}")
        parts.add(hdrLabel(format))
        if (format.frameRate > 0) parts.add(String.format(java.util.Locale.US, "%.3f fps", format.frameRate).replace(".000", ""))
        format.codecs?.let { parts.add(it.substringBefore('.')) }
        decoderLabel?.let { parts.add(it) }
        val mode = currentDisplay()?.mode
        if (mode != null) parts.add("TV ${mode.physicalWidth}×${mode.physicalHeight} @ ${mode.refreshRate.roundToInt()}Hz")
        infoView.text = parts.joinToString("  ·  ")
        infoView.visibility = View.VISIBLE
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ infoView.visibility = View.GONE }, 6000)
    }

    private var decoderLabel: String? = null

    /** Whether Android reports the decoder as the device's video hardware (not a software decoder). */
    private fun isHardwareDecoder(name: String): Boolean {
        val lower = name.lowercase(java.util.Locale.ROOT)
        if (lower.startsWith("omx.google.") || lower.startsWith("c2.android.") || lower.contains("ffmpeg") ||
            lower.contains(".sw.") || lower.endsWith(".sw")) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos
                    .firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?.let { return it.isHardwareAccelerated }
            } catch (ignored: Exception) {
            }
        }
        return true
    }

    private fun hdrLabel(format: Format): String {
        if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) return "Dolby Vision"
        return when (format.colorInfo?.colorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> "HDR10"
            C.COLOR_TRANSFER_HLG -> "HLG"
            else -> "SDR"
        }
    }

    @Suppress("DEPRECATION")
    private fun currentDisplay(): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay

    /**
     * Pick a TV display mode whose refresh rate equals (or is a whole multiple of) the video frame
     * rate, so 24p films play without judder. For 4K videos on TVs whose interface runs at 1080p,
     * a 4K mode is preferred.
     */
    private fun applyBestDisplayMode(format: Format) {
        val display = currentDisplay() ?: return
        val current = display.mode
        val fps = format.frameRate
        val wantHeight = maxOf(format.height, current.physicalHeight)
        val sameOrBigger = display.supportedModes.filter {
            it.physicalHeight >= wantHeight || it.physicalHeight == current.physicalHeight
        }
        fun multiple(mode: Display.Mode): Int {
            val ratio = mode.refreshRate / fps
            val rounded = ratio.roundToInt()
            return if (rounded >= 1 && abs(ratio - rounded) < 0.012f) rounded else 0
        }
        val best = sameOrBigger
            .filter { multiple(it) > 0 }
            .sortedWith(compareBy<Display.Mode>(
                { if (format.height > current.physicalHeight && it.physicalHeight < format.height) 1 else 0 },
                { multiple(it) },
                { abs(it.physicalHeight - wantHeight) }
            ))
            .firstOrNull() ?: return
        if (best.modeId == current.modeId) return
        val attrs = window.attributes
        attrs.preferredDisplayModeId = best.modeId
        window.attributes = attrs
    }

    // ---------------- deinterlacer (line remover, LumenVideoCleanup) ----------------
    private lateinit var linesButton: TextView
    private var linesOn = false
    private var linesDrops = 0
    private var linesDropsSince = 0L

    private fun updateLinesButton() {
        if (!::linesButton.isInitialized) return
        linesButton.text = if (linesOn) "Deinterlace: On" else "Deinterlace: Off"
        val bg = android.graphics.drawable.GradientDrawable()
        bg.cornerRadius = 18 * resources.displayMetrics.density
        bg.setColor(if (linesOn) 0xFF0FA3A3.toInt() else 0xB0000000.toInt())
        if (linesButton.isFocused) bg.setStroke((2 * resources.displayMetrics.density).roundToInt(), 0xFF20C997.toInt())
        linesButton.background = bg
    }

    private fun toggleLines() {
        setLines(!linesOn)
        Toast.makeText(this, if (linesOn) "Deinterlacing on: interlacing lines are removed on the GPU"
            else "Deinterlacing off", Toast.LENGTH_SHORT).show()
    }

    private fun setLines(on: Boolean) {
        val exo = player ?: return
        if (on && isHdr(exo.videoFormat)) {
            Toast.makeText(this, R.string.lumen_deband_hdr, Toast.LENGTH_LONG).show()
            return
        }
        linesOn = on
        updateLinesButton()
        applySurfaceSize()
        val effects = if (on) LumenVideoCleanup.linesOnly() else emptyList()
        if (!effectsPipelineReady) {
            //the video-effects pipeline can only be set up before prepare(): restart at the same spot
            effectsPipelineReady = true
            trackSelector?.let { it.setParameters(it.buildUponParameters().setTunnelingEnabled(false)) }
            val pos = exo.currentPosition
            val play = exo.playWhenReady
            exo.stop()
            exo.setVideoEffects(effects)
            exo.seekTo(pos)
            exo.playWhenReady = play
            exo.prepare()
        } else {
            exo.setVideoEffects(effects)
        }
    }

    private fun updateDebandButton() {
        if (!::debandButton.isInitialized) return
        //"✓ 1234" = frames the GPU filter has processed: proof that it really runs
        val proof = if (debandOn) "  ✓ " + (LumenVideoCleanup.framesFiltered - framesAtStart).coerceAtLeast(0) else ""
        debandButton.text = getString(R.string.lumen_deband_button_s, cleanup.label) + proof
        val bg = android.graphics.drawable.GradientDrawable()
        bg.cornerRadius = 18 * resources.displayMetrics.density
        bg.setColor(if (debandOn) 0xFF0FA3A3.toInt() else 0xB0000000.toInt())
        if (debandButton.isFocused) bg.setStroke((2 * resources.displayMetrics.density).roundToInt(), 0xFF20C997.toInt())
        debandButton.background = bg
    }

    private fun isHdr(format: Format?): Boolean {
        if (format == null) return false
        if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) return true
        val transfer = format.colorInfo?.colorTransfer
        return transfer == C.COLOR_TRANSFER_ST2084 || transfer == C.COLOR_TRANSFER_HLG
    }

    /** "Anime deband" button: on/off for this video only. */
    private var smoothDisplay = true
    private var isTv = false

    /** TV chips have far weaker graphics than tablets: start with the lighter clean-up there. */
    private val defaultOnLevel get() = if (isTv) LumenVideoCleanup.Level.LIGHT else LumenVideoCleanup.Level.ANIME

    /**
     * With filters on, the video is processed at its own resolution and the screen's hardware
     * scaler enlarges it (on a 4K TV the last GPU step would otherwise run at 3840×2160).
     */
    private fun applySurfaceSize() {
        val sv = playerView.videoSurfaceView as? android.view.SurfaceView ?: return
        val size = player?.videoSize
        if ((debandOn || linesOn) && size != null && size.width > 0 && size.height > 0) {
            sv.holder.setFixedSize(size.width, size.height)
        } else {
            sv.holder.setSizeFromLayout()
        }
    }
    private lateinit var displayButton: TextView
    private lateinit var topBar: android.widget.LinearLayout

    private fun updateDisplayButton() {
        if (!::displayButton.isInitialized) return
        displayButton.text = getString(if (smoothDisplay) R.string.lumen_display_smooth else R.string.lumen_display_standard)
        val bg = android.graphics.drawable.GradientDrawable()
        bg.cornerRadius = 18 * resources.displayMetrics.density
        bg.setColor(if (smoothDisplay) 0xFF0FA3A3.toInt() else 0xB0000000.toInt())
        if (displayButton.isFocused) bg.setStroke((2 * resources.displayMetrics.density).roundToInt(), 0xFF20C997.toInt())
        displayButton.background = bg
    }

    /** Switches between the hardware video layer and GPU drawing; the player restarts at the same spot. */
    private fun switchDisplayMode() {
        val next = !smoothDisplay
        getSharedPreferences("lumen_player", MODE_PRIVATE).edit().putBoolean("smooth_display", next).apply()
        val restart = Intent(intent)
            .putExtra(EXTRA_POSITION_MS, player?.currentPosition ?: 0L)
            .putExtra(EXTRA_DEBAND, debandOn)
        Toast.makeText(this, if (next) R.string.lumen_display_smooth_desc else R.string.lumen_display_standard_desc,
            Toast.LENGTH_LONG).show()
        startActivity(restart)
        finish()
    }

    private var framesAtStart = 0L
    private val debandTicker = Handler(Looper.getMainLooper())
    private val debandTick = object : Runnable {
        override fun run() {
            updateDebandButton()
            if (topBar.visibility == View.VISIBLE) debandTicker.postDelayed(this, 1000)
        }
    }

    private fun toggleDeband() {
        val next = LumenVideoCleanup.Level.entries[(cleanup.ordinal + 1) % LumenVideoCleanup.Level.entries.size]
        setCleanup(next, announce = true)
    }

    private fun setCleanup(level: LumenVideoCleanup.Level, announce: Boolean) {
        val exo = player ?: return
        if (level != LumenVideoCleanup.Level.OFF && isHdr(exo.videoFormat)) {
            //the filters would have to convert HDR to SDR: keep HDR untouched
            Toast.makeText(this, R.string.lumen_deband_hdr, Toast.LENGTH_LONG).show()
            return
        }
        cleanup = level
        framesAtStart = LumenVideoCleanup.framesFiltered
        updateDebandButton()
        applySurfaceSize()
        val effects = LumenVideoCleanup.effectsFor(level)
        if (!effectsPipelineReady) {
            //the video-effects pipeline can only be set up before prepare(): restart at the same spot
            effectsPipelineReady = true
            trackSelector?.let { it.setParameters(it.buildUponParameters().setTunnelingEnabled(false)) }
            val pos = exo.currentPosition
            val play = exo.playWhenReady
            exo.stop()
            exo.setVideoEffects(effects)
            exo.seekTo(pos)
            exo.playWhenReady = play
            exo.prepare()
        } else {
            exo.setVideoEffects(effects)
        }
        droppedFramesSince = android.os.SystemClock.elapsedRealtime()
        droppedFrames = 0
        if (announce) {
            Toast.makeText(this, when (level) {
                LumenVideoCleanup.Level.OFF -> R.string.lumen_anime_deband_off
                LumenVideoCleanup.Level.STRONG -> R.string.lumen_deband_strong_desc
                LumenVideoCleanup.Level.LIGHT -> R.string.lumen_deband_light_desc
                else -> R.string.lumen_anime_deband_on_desc
            }, Toast.LENGTH_LONG).show()
        }
    }

    //"intelligent" safety net: if the device can't keep up (many dropped frames), step the level down
    private var droppedFrames = 0
    private var droppedFramesSince = 0L

    private fun onFramesDropped(count: Int) {
        if (linesOn && !debandOn) {
            val t = android.os.SystemClock.elapsedRealtime()
            if (t - linesDropsSince > 20_000) { linesDropsSince = t; linesDrops = 0 }
            linesDrops += count
            if (linesDrops >= 60) {
                linesDrops = 0
                setLines(false)
                Toast.makeText(this, "The TV can't keep up with the deinterlacer here - switched it off (Deinterlace button)",
                    Toast.LENGTH_LONG).show()
            }
            return
        }
        if (!debandOn) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - droppedFramesSince > 20_000) {
            droppedFramesSince = now
            droppedFrames = 0
        }
        droppedFrames += count
        if (droppedFrames >= 40) {
            //one step lighter at a time (Strong → On → Light → Off)
            val lower = LumenVideoCleanup.Level.entries[cleanup.ordinal - 1]
            setCleanup(lower, announce = false)
            if (lower == LumenVideoCleanup.Level.OFF) {
                Toast.makeText(this, R.string.lumen_anime_deband_too_slow, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, getString(R.string.lumen_cleanup_stepped_down, lower.label), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** A readable reason, e.g. "the server refused (HTTP 403)" instead of only an error code. */
    private fun errorText(error: PlaybackException): String {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                return getString(R.string.sofa_player_refused, cause.responseCode) + "\n\n(network: $netStack)"
            }
            cause = cause.cause
        }
        return getString(R.string.sofa_player_error_msg, error.errorCodeName)
    }

    private fun showErrorDialog(error: PlaybackException) {
        val s = stream ?: return
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(R.string.sofa_player_error_title)
            .setMessage(errorText(error))
            .setPositiveButton(R.string.sofa_open_external) { _, _ ->
                SofaVideoHandoff.openExternal(this, s.copy(positionMs = player?.currentPosition ?: 0L))
                finish()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        //Back closes the player (no picture-in-picture, no staying in the background)
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) finish()
            return true
        }
        //Left/Right without the controls: 10 s back/forward (like most TV players)
        if (event.action == KeyEvent.ACTION_DOWN && !playerView.isControllerFullyVisible &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            seekBy(if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -SEEK_STEP_MS else SEEK_STEP_MS)
            return true
        }
        //show controls on any D-pad key, PlayerView handles the rest (media keys, seeking)
        if (event.action == KeyEvent.ACTION_DOWN && !playerView.isControllerFullyVisible &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER ||
                    event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
            playerView.showController()
            return true
        }
        //the Deband button sits above the controls: Up reaches it, Down returns to the controls
        if (::topBar.isInitialized && topBar.hasFocus()) {
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                playerView.showController()
                playerView.requestFocus()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN &&
            playerView.isControllerFullyVisible && topBar.visibility == View.VISIBLE) {
            val focused = currentFocus
            //from the top row of the controls (nothing above inside the player) go to the button
            val next = focused?.focusSearch(View.FOCUS_UP)
            if (next == null || next === playerView || !isDescendant(playerView, next)) {
                linesButton.requestFocus()
                return true
            }
        }
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    private fun isDescendant(parent: View, child: View): Boolean {
        var v: android.view.ViewParent? = child.parent
        while (v != null) {
            if (v === parent) return true
            v = v.parent
        }
        return false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            //true full screen on phones/tablets: hide status and navigation bars
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onStop() {
        super.onStop()
        saveResume()
        player?.pause()
    }

    // ---------------- subtitles ----------------
    private lateinit var subsButton: TextView
    private lateinit var startOverButton: TextView
    private var resumedAt = 0L

    private fun makeBarButton(pad: Int, onClick: () -> Unit): TextView {
        val b = TextView(this)
        b.setTextColor(Color.WHITE)
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        b.setPadding(pad + pad / 2, pad / 2 + 2, pad + pad / 2, pad / 2 + 2)
        b.isFocusable = true
        b.isClickable = true
        b.setOnClickListener { onClick() }
        fun paint() {
            val bg = android.graphics.drawable.GradientDrawable()
            bg.cornerRadius = 18 * resources.displayMetrics.density
            bg.setColor(0xB0000000.toInt())
            if (b.isFocused) bg.setStroke((2 * resources.displayMetrics.density).roundToInt(), 0xFF20C997.toInt())
            b.background = bg
        }
        b.setOnFocusChangeListener { _, _ -> paint() }
        paint()
        return b
    }

    /** Side-loaded subtitle files from the page; the type is guessed from the address. */
    private fun subtitleConfigs(urls: List<String>): List<MediaItem.SubtitleConfiguration> {
        val out = ArrayList<MediaItem.SubtitleConfiguration>()
        val deviceLang = java.util.Locale.getDefault().language
        var defaultChosen = false
        for (u in urls) {
            val lower = u.lowercase(java.util.Locale.ROOT)
            val path = lower.substringBefore('?')
            val mime = when {
                path.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
                path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
                path.endsWith(".ttml") || path.endsWith(".dfxp") || path.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
                else -> MimeTypes.TEXT_VTT
            }
            val lang = Regex("(?:^|[/_.=-])(en|eng|english|es|spa|de|ger|fr|fre|it|ita|pt|por|ru|rus|ar|ara|ja|jpn|hi|hin|id|ind|tr|tur)(?:[/_.&-]|$)")
                .find(path)?.groupValues?.get(1)?.let { normalizeLang(it) }
            val name = Uri.parse(u).lastPathSegment ?: "subtitles"
            val isDefault = !defaultChosen && (lang == null || lang == deviceLang || lang == "en")
            if (isDefault) defaultChosen = true
            out.add(MediaItem.SubtitleConfiguration.Builder(Uri.parse(u))
                .setMimeType(mime)
                .setLanguage(lang)
                .setLabel((lang?.uppercase() ?: "Subtitles") + " · " + name.take(30))
                .setSelectionFlags(if (isDefault) C.SELECTION_FLAG_DEFAULT else 0)
                .build())
        }
        return out
    }

    private fun normalizeLang(l: String): String = when (l) {
        "eng", "english" -> "en"; "spa" -> "es"; "ger" -> "de"; "fre" -> "fr"; "ita" -> "it"; "por" -> "pt"
        "rus" -> "ru"; "ara" -> "ar"; "jpn" -> "ja"; "hin" -> "hi"; "ind" -> "id"; "tur" -> "tr"
        else -> l
    }

    private fun applySubtitleStyle() {
        val sv = playerView.subtitleView ?: return
        val box = config.lumenSubtitleBox
        sv.setStyle(androidx.media3.ui.CaptionStyleCompat(
            Color.WHITE,
            if (box) 0xB0000000.toInt() else Color.TRANSPARENT,
            Color.TRANSPARENT,
            if (box) androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE else androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            Color.BLACK,
            null))
        sv.setApplyEmbeddedFontSizes(false)
        sv.setFractionalTextSize(SUBTITLE_SIZES[config.lumenSubtitleSize.coerceIn(0, 3)])
    }

    private fun updateSubsButton() {
        if (!::subsButton.isInitialized) return
        val names = resources.getStringArray(R.array.lumen_subtitle_sizes)
        subsButton.text = getString(R.string.lumen_subs_button_s, names[config.lumenSubtitleSize.coerceIn(0, 3)])
    }

    private fun cycleSubtitleSize() {
        config.lumenSubtitleSize = (config.lumenSubtitleSize + 1) % 4
        applySubtitleStyle()
        updateSubsButton()
    }

    // ---------------- resume ----------------
    /** The page address identifies an episode best (stream addresses often carry one-time tokens). */
    private fun resumeKey(s: SofaVideoHandoff.Stream): String =
        s.referer?.takeIf { it.startsWith("http") }?.let { it + "#" + s.title } ?: s.url.substringBefore('?')

    private fun saveResume() {
        val exo = player ?: return
        val s = stream ?: return
        val duration = exo.duration
        if (duration == C.TIME_UNSET || duration < 3 * 60_000) return //short clips and live streams
        val pos = exo.currentPosition
        if (pos > 30_000 && pos < duration - 90_000) LumenResume.put(this, resumeKey(s), pos)
        else LumenResume.remove(this, resumeKey(s))
    }

    private fun showResumedNotice() {
        if (resumedAt <= 0) return
        val text = getString(R.string.lumen_resumed_s, formatTime(resumedAt))
        infoView.text = text
        infoView.visibility = View.VISIBLE
        startOverButton.visibility = View.VISIBLE
        handler.postDelayed({ infoView.visibility = View.GONE }, 6000)
        handler.postDelayed({ startOverButton.visibility = View.GONE }, 30_000)
        resumedAt = -1
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60)
    }

    // ---------------- seeking without the controls, touch gestures ----------------
    private fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        val target = (exo.currentPosition + deltaMs).coerceIn(0L, if (exo.duration > 0) exo.duration else Long.MAX_VALUE)
        exo.seekTo(target)
        infoView.text = (if (deltaMs < 0) "⏪ " else "⏩ ") + formatTime(target) +
                (if (exo.duration > 0) " / " + formatTime(exo.duration) else "")
        infoView.visibility = View.VISIBLE
        handler.removeCallbacksAndMessages(HIDE_INFO_TOKEN)
        handler.postAtTime({ infoView.visibility = View.GONE }, HIDE_INFO_TOKEN, android.os.SystemClock.uptimeMillis() + 1500)
    }

    private val HIDE_INFO_TOKEN = Any()

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchGestures() {
        val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        var mode = 0 //0 none, 1 volume (right half), 2 brightness (left half)
        var startY = 0f
        var startVolume = 0
        var startBrightness = 0.5f
        val detector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                //double tap on the left/right third: 10 s back/forward
                val w = playerView.width
                when {
                    e.x < w / 3f -> seekBy(-SEEK_STEP_MS)
                    e.x > w * 2 / 3f -> seekBy(SEEK_STEP_MS)
                    else -> player?.let { it.playWhenReady = !it.playWhenReady }
                }
                return true
            }
        })
        playerView.setOnTouchListener { _, e ->
            detector.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mode = 0
                    startY = e.y
                    startVolume = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                    startBrightness = window.attributes.screenBrightness.takeIf { it >= 0f } ?: 0.5f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = startY - e.y
                    val h = playerView.height.coerceAtLeast(1)
                    if (mode == 0 && kotlin.math.abs(dy) > h / 12f) mode = if (e.x > playerView.width / 2f) 1 else 2
                    if (mode == 1) {
                        val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                        val v = (startVolume + dy / h * max * 1.5f).roundToInt().coerceIn(0, max)
                        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0)
                        infoView.text = "🔊 " + (v * 100 / max.coerceAtLeast(1)) + "%"
                        infoView.visibility = View.VISIBLE
                    } else if (mode == 2) {
                        val b = (startBrightness + dy / h * 1.2f).coerceIn(0.02f, 1f)
                        val lp = window.attributes
                        lp.screenBrightness = b
                        window.attributes = lp
                        infoView.text = "☀ " + (b * 100).roundToInt() + "%"
                        infoView.visibility = View.VISIBLE
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (mode != 0) handler.postDelayed({ infoView.visibility = View.GONE }, 800)
                }
            }
            mode != 0 //a swipe is ours; taps still show/hide the controls
        }
    }

    // ---------------- picture-in-picture ----------------
    //Lumen: no automatic picture-in-picture. On some TVs Back also counts as "leaving", so the
    //player shrank into a corner window instead of closing.
    @Suppress("unused")
    private fun enterPipNow() {
        val exo = player ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !exo.isPlaying) return
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        try {
            val vs = exo.videoSize
            val ratio = if (vs.width > 0 && vs.height > 0)
                android.util.Rational(vs.width, vs.height).let {
                    //Android only allows ratios between 1:2.39 and 2.39:1
                    val r = it.toFloat()
                    if (r > 2.39f) android.util.Rational(239, 100) else if (r < 0.42f) android.util.Rational(100, 239) else it
                } else android.util.Rational(16, 9)
            enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(ratio).build())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            playerView.hideController()
            playerView.useController = false
            topBar.visibility = View.GONE
            infoView.visibility = View.GONE
        } else {
            playerView.useController = true
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }
}
