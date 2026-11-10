package fr.pelis.iptv.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import android.os.Handler
import android.os.Looper
import fr.pelis.iptv.R
import fr.pelis.iptv.data.Library
import fr.pelis.iptv.data.RecentChannel
import fr.pelis.iptv.data.WatchProgress
import fr.pelis.iptv.data.XtreamClient

/**
 * Lecteur plein écran (ExoPlayer) : direct, films et épisodes, avec mode Picture-in-Picture.
 * Pour le direct, la liste contient toute la catégorie : boutons suivant/précédent
 * ou touches CH+/CH- de la télécommande pour zapper.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var topBar: LinearLayout
    private lateinit var titleView: TextView

    private var items: List<PlayItem> = emptyList()
    private var activeUrls: MutableList<String> = mutableListOf()
    private val fallbackUsed = HashSet<Int>()
    private var resumeIndex = 0
    private var resumePosition = C.TIME_UNSET

    /** Pour la touche « chaîne précédente ». */
    private var currentIndex = -1
    private var previousIndex = -1
    private lateinit var lastChannelButton: ImageButton

    private val handler = Handler(Looper.getMainLooper())
    private val progressSaver = object : Runnable {
        override fun run() {
            saveProgress()
            handler.postDelayed(this, 10_000)
        }
    }

    private val pipSupported by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }
    private var inPip = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Library.init(this)
        if (!loadPlaylist()) {
            finish()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(56, 28, 32, 28)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP,
            )
            addView(titleView)
            lastChannelButton = ImageButton(this@PlayerActivity).apply {
                setImageResource(R.drawable.ic_last_channel)
                setBackgroundColor(Color.TRANSPARENT)
                contentDescription = "Chaîne précédente"
                setPadding(24, 24, 24, 24)
                visibility = View.GONE
                setOnClickListener { goToPreviousChannel() }
            }
            addView(lastChannelButton)
            if (pipSupported) {
                addView(ImageButton(this@PlayerActivity).apply {
                    setImageResource(R.drawable.ic_pip)
                    setBackgroundColor(Color.TRANSPARENT)
                    contentDescription = "Picture-in-Picture"
                    setPadding(24, 24, 24, 24)
                    setOnClickListener { enterPip() }
                })
            }
        }
        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            keepScreenOn = true
            setShowSubtitleButton(true)
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                topBar.visibility = if (inPip) View.GONE else visibility
                if (visibility != View.VISIBLE) hideSystemUi()
            })
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(playerView)
            addView(topBar)
        })
        hideSystemUi()

        addOnPictureInPictureModeChangedListener { info -> onPipModeChanged(info.isInPictureInPictureMode) }
        ContextCompat.registerReceiver(
            this, pipReceiver, IntentFilter(ACTION_PIP_CONTROL), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Nouvelle chaîne / vidéo lancée alors que le lecteur est déjà ouvert (ex. en PiP). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        saveProgress()
        if (!loadPlaylist()) return
        val p = player
        if (p == null) {
            initializePlayer()
        } else {
            p.setMediaItems(items.indices.map { mediaItem(it) }, resumeIndex, resumePosition)
            p.prepare()
            p.play()
            updateTitle()
        }
    }

    private fun loadPlaylist(): Boolean {
        items = PlaylistHolder.items
        if (items.isEmpty()) return false
        activeUrls = items.map { it.url }.toMutableList()
        fallbackUsed.clear()
        resumeIndex = PlaylistHolder.startIndex
        resumePosition = PlaylistHolder.startPositionMs.takeIf { it > 0 } ?: C.TIME_UNSET
        currentIndex = -1
        previousIndex = -1
        if (::lastChannelButton.isInitialized) updateLastChannelButton()
        return true
    }

    override fun onStart() {
        super.onStart()
        if (items.isNotEmpty() && player == null) initializePlayer()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(pipReceiver) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 8 à 11 : on bascule en PiP en quittant l'appli (Android 12+ le fait via setAutoEnterEnabled).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && player?.isPlaying == true) enterPip()
    }

    // ---------- Lecteur ----------

    private fun initializePlayer() {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(XtreamClient.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
            .setConstantBitrateSeekingEnabled(true)
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)

        val exo = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, extractors))
            .build()
        exo.addListener(listener)
        exo.setMediaItems(items.indices.map { mediaItem(it) }, resumeIndex, resumePosition)
        exo.playWhenReady = true
        exo.prepare()
        playerView.player = exo
        player = exo
        onItemChanged()
        updatePipParams()
        handler.postDelayed(progressSaver, 10_000)
    }

    private fun releasePlayer() {
        val p = player ?: return
        handler.removeCallbacks(progressSaver)
        saveProgress()
        resumeIndex = p.currentMediaItemIndex
        resumePosition = if (items.getOrNull(resumeIndex)?.isLive == true) C.TIME_UNSET else p.currentPosition
        p.removeListener(listener)
        p.release()
        player = null
        playerView.player = null
    }

    private fun mediaItem(index: Int): MediaItem {
        val url = activeUrls[index]
        return MediaItem.Builder()
            .setUri(url)
            .setMediaId(index.toString())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(items[index].title).build())
            .apply { if (url.endsWith(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = onItemChanged()

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveProgress()
            updatePipParams()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) = updatePipParams()

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            // Le flux .ts ne passe pas ? On retente automatiquement en HLS (.m3u8).
            val index = p.currentMediaItemIndex
            val fallback = items.getOrNull(index)?.fallbackUrl
            if (fallback != null && fallbackUsed.add(index)) {
                activeUrls[index] = fallback
                p.replaceMediaItem(index, mediaItem(index))
                p.prepare()
                p.play()
                return
            }
            Toast.makeText(
                this@PlayerActivity,
                "Lecture impossible (${error.errorCodeName})",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun updateTitle() {
        val index = player?.currentMediaItemIndex ?: resumeIndex
        titleView.text = items.getOrNull(index)?.title.orEmpty()
    }

    /** Appelé à chaque changement de chaîne / vidéo. */
    private fun onItemChanged() {
        val index = player?.currentMediaItemIndex ?: return
        if (index != currentIndex) {
            if (currentIndex >= 0) previousIndex = currentIndex
            currentIndex = index
        }
        updateTitle()
        updateLastChannelButton()
        val item = items.getOrNull(index) ?: return
        if (item.isLive && item.contentId != null) {
            Library.addRecentChannel(RecentChannel(item.contentId, item.title, item.image, item.epgId))
        }
    }

    private fun updateLastChannelButton() {
        val live = items.firstOrNull()?.isLive == true
        lastChannelButton.visibility =
            if (live && previousIndex in items.indices && previousIndex != currentIndex) View.VISIBLE else View.GONE
    }

    /** Bascule entre la chaîne actuelle et la précédente. */
    private fun goToPreviousChannel() {
        val p = player ?: return
        if (previousIndex in items.indices && previousIndex != p.currentMediaItemIndex) {
            p.seekToDefaultPosition(previousIndex)
        }
    }

    /** Mémorise la position du film / épisode en cours pour « Continuer à regarder ». */
    private fun saveProgress() {
        val p = player ?: return
        val item = items.getOrNull(p.currentMediaItemIndex) ?: return
        val key = item.progressKey ?: return
        val duration = p.duration
        if (item.isLive || duration == C.TIME_UNSET || duration <= 0) return
        Library.saveProgress(
            WatchProgress(
                key = key,
                kind = item.kind ?: "vod",
                contentId = item.contentId ?: return,
                extension = item.extension,
                title = item.libraryTitle ?: item.title,
                subtitle = item.librarySubtitle,
                image = item.image,
                position = p.currentPosition,
                duration = duration,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    // ---------- Picture-in-Picture ----------

    private fun enterPip() {
        if (!pipSupported || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { enterPictureInPictureMode(buildPipParams()) }
    }

    private fun updatePipParams() {
        if (!pipSupported || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { setPictureInPictureParams(buildPipParams()) }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        val size = player?.videoSize
        if (size != null && size.width > 0 && size.height > 0) {
            val ratio = size.width.toFloat() * size.pixelWidthHeightRatio / size.height
            builder.setAspectRatio(
                when {
                    ratio > 2.39f -> Rational(239, 100)
                    ratio < 1f / 2.39f -> Rational(100, 239)
                    else -> Rational((ratio * 1000).toInt(), 1000)
                },
            )
        } else {
            builder.setAspectRatio(Rational(16, 9))
        }
        val rect = Rect()
        if (playerView.getGlobalVisibleRect(rect)) builder.setSourceRectHint(rect)

        val playing = player?.isPlaying == true
        val actions = mutableListOf<RemoteAction>()
        val multi = items.size > 1
        if (multi) actions += pipAction(CONTROL_PREVIOUS, R.drawable.ic_skip_previous, "Précédent")
        actions += if (playing) pipAction(CONTROL_PLAY_PAUSE, R.drawable.ic_pause, "Pause")
        else pipAction(CONTROL_PLAY_PAUSE, R.drawable.ic_play, "Lecture")
        if (multi) actions += pipAction(CONTROL_NEXT, R.drawable.ic_skip_next, "Suivant")
        builder.setActions(actions)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(playing)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun pipAction(control: Int, icon: Int, label: String): RemoteAction {
        val intent = Intent(ACTION_PIP_CONTROL).setPackage(packageName).putExtra(EXTRA_CONTROL, control)
        val pending = PendingIntent.getBroadcast(
            this, control, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return RemoteAction(Icon.createWithResource(this, icon), label, label, pending)
    }

    private fun onPipModeChanged(isInPip: Boolean) {
        inPip = isInPip
        playerView.useController = !isInPip
        if (isInPip) {
            playerView.hideController()
            topBar.visibility = View.GONE
        } else if (lifecycle.currentState == Lifecycle.State.CREATED) {
            // La fenêtre PiP a été fermée par l'utilisateur.
            finishAndRemoveTask()
        } else {
            hideSystemUi()
        }
    }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val p = player ?: return
            when (intent.getIntExtra(EXTRA_CONTROL, 0)) {
                CONTROL_PLAY_PAUSE -> if (p.isPlaying) p.pause() else p.play()
                CONTROL_NEXT -> if (p.hasNextMediaItem()) p.seekToNextMediaItem()
                CONTROL_PREVIOUS -> if (p.hasPreviousMediaItem()) p.seekToPreviousMediaItem()
            }
        }
    }

    // ---------- Télécommande / système ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player
        if (p != null && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                    if (p.hasNextMediaItem()) p.seekToNextMediaItem()
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                    if (p.hasPreviousMediaItem()) p.seekToPreviousMediaItem()
                    return true
                }
                KeyEvent.KEYCODE_LAST_CHANNEL -> {
                    goToPreviousChannel()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun hideSystemUi() {
        WindowInsetsControllerCompat(window, window.decorView).run {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private companion object {
        const val ACTION_PIP_CONTROL = "fr.pelis.iptv.PIP_CONTROL"
        const val EXTRA_CONTROL = "control"
        const val CONTROL_PLAY_PAUSE = 1
        const val CONTROL_NEXT = 2
        const val CONTROL_PREVIOUS = 3
    }
}
