package fr.pelis.iptv.player

import android.annotation.SuppressLint
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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
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
import android.net.Uri
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import fr.pelis.iptv.R
import fr.pelis.iptv.cast.CastSupport
import fr.pelis.iptv.DEVICE_TV
import fr.pelis.iptv.data.Library
import fr.pelis.iptv.data.SessionStore
import fr.pelis.iptv.data.RecentChannel
import fr.pelis.iptv.data.WatchProgress
import fr.pelis.iptv.data.XtreamClient

/**
 * Lecteur plein écran (ExoPlayer) : direct, films et épisodes, avec mode Picture-in-Picture
 * et envoi vers un Chromecast (CastPlayer) quand une session Cast est active.
 * Pour le direct, la liste contient toute la catégorie : boutons suivant/précédent
 * ou touches CH+/CH- de la télécommande pour zapper.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : FragmentActivity() {

    /** Lecteur local (téléphone / TV). */
    private var player: ExoPlayer? = null

    /** Lecteur distant (Chromecast), null si Google Cast est indisponible. */
    private var castPlayer: CastPlayer? = null
    private var casting = false
    /** Index (dans [items]) du premier élément envoyé au Chromecast (file d'attente limitée). */
    private var castOffset = 0
    private var lastCastIndex = 0
    private var lastCastPosition = C.TIME_UNSET
    private lateinit var castBanner: TextView

    /** Lecteur actuellement utilisé. */
    private val active: Player? get() = if (casting) castPlayer else player

    /** Index réel (dans [items]) de l'élément en cours. */
    private fun activeIndex(): Int? = active?.let { it.currentMediaItemIndex + if (casting) castOffset else 0 }
    private lateinit var playerView: PlayerView
    private lateinit var topBar: LinearLayout
    private lateinit var titleView: TextView

    private var items: List<PlayItem> = emptyList()
    /** Profil qui a lancé la lecture : progression et historique sont écrits dans celui-ci. */
    private var profileId: String? = null
    private var activeUrls: MutableList<String> = mutableListOf()
    private val fallbackUsed = HashSet<Int>()
    private var resumeIndex = 0
    private var resumePosition = C.TIME_UNSET

    /** Pour la touche « chaîne précédente ». */
    private var currentIndex = -1
    private var previousIndex = -1
    private lateinit var lastChannelButton: ImageButton
    private lateinit var channelListButton: ImageButton

    private val handler = Handler(Looper.getMainLooper())
    private val progressSaver = object : Runnable {
        override fun run() {
            saveProgress()
            if (casting) rememberCastState()
            handler.postDelayed(this, 5_000)
        }
    }

    private val pipSupported by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }
    private var inPip = false

    /** Mode Téléviseur choisi au premier démarrage : pas de PiP ni de Cast, zapping haut/bas. */
    private var tvMode = false
    private lateinit var zapOverlay: TextView
    private lateinit var errorPanel: LinearLayout
    private lateinit var errorText: TextView

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
    private val hideZapOverlay = Runnable { zapOverlay.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Library.init(this)
        tvMode = SessionStore(this).deviceMode == DEVICE_TV
        // savedInstanceState != null : activité recréée (ex. après l'arrêt du processus en arrière-plan).
        if (!loadPlaylist(savedInstanceState)) {
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
            setPadding(20.dp, 10.dp, 12.dp, 10.dp)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP,
            )
            addView(titleView)
            lastChannelButton = ImageButton(this@PlayerActivity).apply {
                setImageResource(R.drawable.ic_last_channel)
                setBackgroundColor(Color.TRANSPARENT)
                contentDescription = "Chaîne précédente"
                setPadding(8.dp, 8.dp, 8.dp, 8.dp)
                visibility = View.GONE
                setOnClickListener { goToPreviousChannel() }
            }
            addView(lastChannelButton)
            channelListButton = ImageButton(this@PlayerActivity).apply {
                setImageResource(R.drawable.ic_channel_list)
                setBackgroundColor(Color.TRANSPARENT)
                contentDescription = "Liste des chaînes"
                setPadding(8.dp, 8.dp, 8.dp, 8.dp)
                visibility = if (items.firstOrNull()?.isLive == true && items.size > 1) View.VISIBLE else View.GONE
                setOnClickListener { showChannelPicker() }
            }
            addView(channelListButton)
            if (!tvMode) CastSupport.createButton(this@PlayerActivity)?.let { addView(it) }
            if (pipSupported && !tvMode) {
                addView(ImageButton(this@PlayerActivity).apply {
                    setImageResource(R.drawable.ic_pip)
                    setBackgroundColor(Color.TRANSPARENT)
                    contentDescription = "Picture-in-Picture"
                    setPadding(8.dp, 8.dp, 8.dp, 8.dp)
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
        castBanner = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(16.dp, 8.dp, 16.dp, 8.dp)
            maxWidth = (resources.displayMetrics.widthPixels * 0.8).toInt()
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
            )
        }
        zapOverlay = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            setBackgroundColor(0xB0000000.toInt())
            setPadding(16.dp, 8.dp, 16.dp, 8.dp)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START,
            ).apply { setMargins(24.dp, 0, 0, 24.dp) }
        }
        errorText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
        }
        errorPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xE0202020.toInt())
            setPadding(24.dp, 20.dp, 24.dp, 16.dp)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
            )
            addView(errorText)
            addView(LinearLayout(this@PlayerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, 12.dp, 0, 0)
                val retry = Button(this@PlayerActivity).apply {
                    text = "Réessayer"
                    setOnClickListener { retryPlayback() }
                }
                addView(retry)
                addView(Button(this@PlayerActivity).apply {
                    text = "Retour"
                    setOnClickListener { finish() }
                })
            })
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(playerView)
            addView(castBanner)
            addView(zapOverlay)
            addView(topBar)
            addView(errorPanel)
        })
        hideSystemUi()

        // Mode Téléviseur : pas de Cast, inutile d'initialiser Google Cast.
        if (!tvMode) CastSupport.context(this)?.let { ctx ->
            castPlayer = CastPlayer(ctx).apply {
                addListener(listener)
                setSessionAvailabilityListener(castSessionListener)
            }
            casting = castPlayer?.isCastSessionAvailable == true
        }

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
        if (casting) {
            loadOnCast(resumeIndex, resumePosition)
        } else if (p == null) {
            initializePlayer()
        } else {
            p.setMediaItems(items.indices.map { mediaItem(it) }, resumeIndex, resumePosition)
            p.prepare()
            p.play()
            updateTitle()
        }
    }

    private fun loadPlaylist(saved: Bundle? = null): Boolean {
        val session = (if (saved != null) PlaylistHolder.restore(this) else PlaylistHolder.current) ?: return false
        if (session.items.isEmpty()) return false
        items = session.items
        profileId = session.profileId
        activeUrls = items.map { it.url }.toMutableList()
        fallbackUsed.clear()
        resumeIndex = (saved?.getInt(STATE_INDEX, session.startIndex) ?: session.startIndex).coerceIn(0, items.lastIndex)
        resumePosition = (saved?.getLong(STATE_POSITION, session.startPositionMs) ?: session.startPositionMs)
            .takeIf { it > 0 } ?: C.TIME_UNSET
        currentIndex = -1
        previousIndex = -1
        if (::lastChannelButton.isInitialized) updateLastChannelButton()
        return true
    }

    /** Élément et position en cours, pour recréer le lecteur au même endroit. */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (items.isEmpty()) return
        val index = activeIndex() ?: resumeIndex
        val p = active
        val position = if (p != null && p.mediaItemCount > 0) p.currentPosition else resumePosition
        outState.putInt(STATE_INDEX, index)
        outState.putLong(STATE_POSITION, if (items.getOrNull(index)?.isLive == true) -1 else position)
    }

    override fun onStart() {
        super.onStart()
        if (items.isEmpty()) return
        if (casting) {
            if (playerView.player == null) loadOnCast(resumeIndex, resumePosition)
        } else if (player == null) {
            initializePlayer()
        }
    }

    override fun onStop() {
        super.onStop()
        // En Cast, la lecture continue sur la TV : on ne coupe que le lecteur local.
        if (casting) saveProgress() else releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(progressSaver)
        runCatching { unregisterReceiver(pipReceiver) }
        castPlayer?.let {
            it.setSessionAvailabilityListener(null)
            it.removeListener(listener)
            it.release()
        }
        castPlayer = null
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 8 à 11 : on bascule en PiP en quittant l'appli (Android 12+ le fait via setAutoEnterEnabled).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !tvMode && !casting && player?.isPlaying == true) enterPip()
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

        val audio = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        val exo = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, extractors))
            // Pause pendant un appel / une autre appli audio, et quand le casque est débranché.
            .setAudioAttributes(audio, true)
            .setHandleAudioBecomingNoisy(true)
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

    // ---------- Chromecast ----------

    private val castSessionListener = object : SessionAvailabilityListener {
        override fun onCastSessionAvailable() {
            val local = player
            val index = local?.currentMediaItemIndex ?: resumeIndex
            val position = if (local == null || items.getOrNull(index)?.isLive == true) resumePosition
            else local.currentPosition
            // Le lecteur local connaît le format du son : on le note avant de le libérer.
            local?.let { rememberAudioFormat(index, it) }
            releasePlayer()
            casting = true
            loadOnCast(index, position)
        }

        override fun onCastSessionUnavailable() {
            if (!casting) return
            rememberCastState()
            saveProgress()
            handler.removeCallbacks(progressSaver)
            casting = false
            castBanner.visibility = View.GONE
            playerView.player = null
            resumeIndex = lastCastIndex.coerceIn(0, items.lastIndex)
            resumePosition = if (items[resumeIndex].isLive) C.TIME_UNSET else lastCastPosition
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) initializePlayer()
        }
    }

    /** Envoie la liste au Chromecast (fenêtre de [CAST_WINDOW] éléments autour de [index]). */
    private fun loadOnCast(index: Int, position: Long) {
        val c = castPlayer ?: return
        val start = (index - CAST_WINDOW / 2).coerceIn(0, maxOf(0, items.size - CAST_WINDOW))
        val end = minOf(items.size, start + CAST_WINDOW)
        castOffset = start
        val startPosition = if (position == C.TIME_UNSET || items[index].isLive) 0L else position
        c.setMediaItems((start until end).map { castMediaItem(it) }, index - start, startPosition)
        c.playWhenReady = true
        c.prepare()
        playerView.player = c
        playerView.showController()
        castBanner.visibility = View.VISIBLE
        updateCastBanner(index)
        castAudioWarning(index)?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        lastCastIndex = index
        lastCastPosition = startPosition
        onItemChanged()
        handler.removeCallbacks(progressSaver)
        handler.postDelayed(progressSaver, 5_000)
    }

    private fun rememberCastState() {
        val c = castPlayer ?: return
        if (c.mediaItemCount == 0) return
        lastCastIndex = c.currentMediaItemIndex + castOffset
        lastCastPosition = c.currentPosition
    }

    /**
     * Mémorise le format de la première piste audio (celle que le Chromecast lira)
     * de l'élément [index], lu depuis le lecteur local.
     */
    private fun rememberAudioFormat(index: Int, local: Player) {
        val item = items.getOrNull(index) ?: return
        val audio = local.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO } ?: return
        val mime = audio.getTrackFormat(0).sampleMimeType ?: return
        audioFormats[formatKey(item)] = mime
    }

    /** Message d'avertissement si le son de l'élément [index] ne peut pas être lu par le Chromecast. */
    private fun castAudioWarning(index: Int): String? {
        val item = items.getOrNull(index) ?: return null
        if (castMediaItemUrl(item).endsWith(".m3u8", ignoreCase = true)) return null
        val codec = CAST_UNSUPPORTED_AUDIO[audioFormats[formatKey(item)] ?: return null] ?: return null
        return "Le son de cette vidéo est en $codec : la plupart des Chromecast ne le décodent pas dans ce " +
            "type de fichier, l'image risque de passer sans le son. Coupez le Cast pour continuer sur le téléphone."
    }

    private fun updateCastBanner(index: Int) {
        val device = CastSupport.context(this)?.sessionManager?.currentCastSession?.castDevice?.friendlyName
        val warning = castAudioWarning(index)
        castBanner.text = buildString {
            append("Lecture sur ${device ?: "Chromecast"}")
            if (warning != null) append("\n\n⚠ ").append(warning)
        }
        castBanner.setBackgroundColor(if (warning != null) 0xCC5D1A1A.toInt() else Color.TRANSPARENT)
    }

    /** Clé du cache des formats audio : identifiant du contenu, jamais l'URL (qui contient les identifiants). */
    private fun formatKey(item: PlayItem) = "${item.kind ?: if (item.isLive) "live" else "vod"}:${item.contentId ?: item.title}"

    private fun castMediaItemUrl(item: PlayItem) = if (item.isLive) item.fallbackUrl ?: item.url else item.url

    /**
     * Élément pour le récepteur Cast par défaut : il ne lit pas le MPEG-TS brut,
     * donc le direct est envoyé en HLS (.m3u8).
     */
    private fun castMediaItem(index: Int): MediaItem {
        val item = items[index]
        val url = castMediaItemUrl(item)
        val mime = when {
            url.endsWith(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
            url.endsWith(".mkv", ignoreCase = true) -> MimeTypes.VIDEO_MATROSKA
            url.endsWith(".ts", ignoreCase = true) -> MimeTypes.VIDEO_MP2T
            url.endsWith(".webm", ignoreCase = true) -> MimeTypes.VIDEO_WEBM
            else -> MimeTypes.VIDEO_MP4
        }
        return MediaItem.Builder()
            .setUri(url)
            .setMediaId(index.toString())
            .setMimeType(mime)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.libraryTitle ?: item.title)
                    .setSubtitle(item.librarySubtitle)
                    .setArtworkUri(item.image?.let(Uri::parse))
                    .build(),
            )
            .build()
    }

    /** Va à l'élément [real] de la liste (recharge la file Cast si besoin). */
    private fun seekToItem(real: Int) {
        val p = active ?: return
        if (real !in items.indices) return
        val local = if (casting) real - castOffset else real
        if (casting && local !in 0 until p.mediaItemCount) loadOnCast(real, C.TIME_UNSET)
        else p.seekToDefaultPosition(local)
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
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Épisode terminé et enchaîné automatiquement : il sort de « Continuer à regarder ».
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) markFinished(currentIndex)
            onItemChanged()
        }

        override fun onTracksChanged(tracks: Tracks) {
            val local = player ?: return
            if (!casting) rememberAudioFormat(local.currentMediaItemIndex, local)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveProgress()
            updatePipParams()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) = updatePipParams()

        override fun onPlayerError(error: PlaybackException) {
            if (casting) {
                Toast.makeText(
                    this@PlayerActivity,
                    "Le Chromecast ne peut pas lire ce flux (format ou serveur incompatible)",
                    Toast.LENGTH_LONG,
                ).show()
                return
            }
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
            showError(error)
        }
    }

    /** Message d'erreur affiché tant que l'utilisateur n'a pas choisi « Réessayer » ou « Retour ». */
    private fun showError(error: PlaybackException) {
        val reason = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Connexion au serveur impossible. Vérifiez votre réseau."
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Le serveur refuse ce flux (abonnement, nombre de connexions ou flux hors ligne)."
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Ce contenu n'existe plus sur le serveur."
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "Le fichier envoyé par le serveur est vide ou illisible."
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "Ce format vidéo n'est pas pris en charge par l'appareil."
            else -> "Lecture impossible."
        }
        errorText.text = "$reason\n(${error.errorCodeName})"
        errorPanel.visibility = View.VISIBLE
        errorPanel.getChildAt(1)?.let { (it as? ViewGroup)?.getChildAt(0)?.requestFocus() }
    }

    private fun hideError() {
        if (::errorPanel.isInitialized) errorPanel.visibility = View.GONE
    }

    private fun retryPlayback() {
        hideError()
        val p = player ?: return initializePlayer()
        val index = p.currentMediaItemIndex
        if (items.getOrNull(index)?.isLive == true) p.seekToDefaultPosition(index)
        p.prepare()
        p.play()
    }

    /** Élément lu jusqu'au bout : retiré de « Continuer à regarder ». */
    private fun markFinished(index: Int) {
        val owner = profileId ?: return
        val key = items.getOrNull(index)?.progressKey ?: return
        Library.removeProgress(owner, key)
    }

    private fun updateTitle() {
        val index = activeIndex() ?: resumeIndex
        titleView.text = items.getOrNull(index)?.title.orEmpty()
    }

    /** Appelé à chaque changement de chaîne / vidéo. */
    private fun onItemChanged() {
        val index = activeIndex() ?: return
        if (index != currentIndex) {
            if (currentIndex >= 0) previousIndex = currentIndex
            currentIndex = index
        }
        hideError()
        updateTitle()
        updateLastChannelButton()
        if (casting) updateCastBanner(index)
        val item = items.getOrNull(index) ?: return
        if (tvMode && item.isLive && !playerView.isControllerFullyVisible) showZapOverlay(index)
        val owner = profileId
        if (owner != null && item.isLive && item.contentId != null) {
            Library.addRecentChannel(owner, RecentChannel(item.contentId, item.title, item.image, item.epgId))
        }
    }

    /** Bandeau « numéro + nom de chaîne » affiché 3 s après un changement de chaîne. */
    private fun showZapOverlay(index: Int) {
        zapOverlay.text = "${index + 1}  ${items[index].title}"
        zapOverlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideZapOverlay)
        handler.postDelayed(hideZapOverlay, 3_000)
    }

    private fun updateLastChannelButton() {
        val live = items.firstOrNull()?.isLive == true
        lastChannelButton.visibility =
            if (live && previousIndex in items.indices && previousIndex != currentIndex) View.VISIBLE else View.GONE
        if (::channelListButton.isInitialized) {
            channelListButton.visibility = if (live && items.size > 1) View.VISIBLE else View.GONE
        }
    }

    /**
     * Liste des chaînes de la catégorie : un clic bascule directement sur la chaîne choisie,
     * aussi bien sur le téléphone que sur le Chromecast.
     */
    private fun showChannelPicker() {
        if (items.firstOrNull()?.isLive != true || items.size < 2) return
        val current = currentIndex.coerceIn(0, items.lastIndex)
        val names = items.mapIndexed { i, item -> "${i + 1}.  ${item.title}" }.toTypedArray()
        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(if (casting) "Changer de chaîne sur la TV" else "Changer de chaîne")
            .setSingleChoiceItems(names, current) { d, which ->
                d.dismiss()
                if (which != activeIndex()) seekToItem(which)
            }
            .setNegativeButton("Fermer", null)
            .create()
        dialog.setOnDismissListener { hideSystemUi() }
        dialog.show()
        dialog.listView?.setSelection(current)
    }

    /** Chaîne suivante / précédente dans toute la liste (y compris au-delà de la file Cast). */
    private fun zap(delta: Int) {
        val target = (currentIndex.takeIf { it >= 0 } ?: return) + delta
        if (target in items.indices) seekToItem(target)
    }

    /** Bascule entre la chaîne actuelle et la précédente. */
    private fun goToPreviousChannel() {
        if (previousIndex in items.indices && previousIndex != activeIndex()) seekToItem(previousIndex)
    }

    /** Mémorise la position du film / épisode en cours pour « Continuer à regarder ». */
    private fun saveProgress() {
        val p = active ?: return
        if (p.mediaItemCount == 0) return
        val item = items.getOrNull(activeIndex() ?: return) ?: return
        val key = item.progressKey ?: return
        val owner = profileId ?: return
        val duration = p.duration
        if (item.isLive || duration == C.TIME_UNSET || duration <= 0) return
        Library.saveProgress(
            owner,
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
                seriesId = item.seriesId,
                season = item.season,
            ),
        )
    }

    // ---------- Picture-in-Picture ----------

    private fun enterPip() {
        if (tvMode || !pipSupported || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { enterPictureInPictureMode(buildPipParams()) }
    }

    private fun updatePipParams() {
        if (tvMode || !pipSupported || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
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

        val playing = active?.isPlaying == true
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
            val p = active ?: return
            when (intent.getIntExtra(EXTRA_CONTROL, 0)) {
                CONTROL_PLAY_PAUSE -> if (p.isPlaying) p.pause() else p.play()
                CONTROL_NEXT -> if (items.firstOrNull()?.isLive == true) zap(1)
                    else if (p.hasNextMediaItem()) p.seekToNextMediaItem()
                CONTROL_PREVIOUS -> if (items.firstOrNull()?.isLive == true) zap(-1)
                    else if (p.hasPreviousMediaItem()) p.seekToPreviousMediaItem()
            }
        }
    }

    // ---------- Télécommande / système ----------

    // Faux positif de lint : dispatchKeyEvent est une méthode publique d'Activity.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = active
        if (p != null && event.action == KeyEvent.ACTION_DOWN) {
            val live = items.firstOrNull()?.isLive == true
            if (tvMode && live && !playerView.isControllerFullyVisible) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        zap(1)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        zap(-1)
                        return true
                    }
                }
            }
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                    if (live) zap(1) else if (p.hasNextMediaItem()) p.seekToNextMediaItem()
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                    if (live) zap(-1) else if (p.hasPreviousMediaItem()) p.seekToPreviousMediaItem()
                    return true
                }
                KeyEvent.KEYCODE_LAST_CHANNEL -> {
                    goToPreviousChannel()
                    return true
                }
                KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_MENU -> if (live && items.size > 1) {
                    showChannelPicker()
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
        /** Taille maximale de la file d'attente envoyée au Chromecast. */
        const val CAST_WINDOW = 20

        /** Format audio connu par contenu (rempli pendant la lecture locale), 100 entrées au plus. */
        val audioFormats = object : LinkedHashMap<String, String>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 100
        }
        const val STATE_INDEX = "player_index"
        const val STATE_POSITION = "player_position"

        /** Formats audio que le récepteur Cast par défaut ne décode pas dans un fichier (MKV, MP4…). */
        val CAST_UNSUPPORTED_AUDIO = mapOf(
            MimeTypes.AUDIO_AC3 to "Dolby Digital (AC3)",
            MimeTypes.AUDIO_E_AC3 to "Dolby Digital Plus (E-AC3)",
            MimeTypes.AUDIO_E_AC3_JOC to "Dolby Atmos (E-AC3 JOC)",
            MimeTypes.AUDIO_AC4 to "Dolby AC-4",
            MimeTypes.AUDIO_TRUEHD to "Dolby TrueHD",
            MimeTypes.AUDIO_DTS to "DTS",
            MimeTypes.AUDIO_DTS_HD to "DTS-HD",
            MimeTypes.AUDIO_DTS_EXPRESS to "DTS Express",
            MimeTypes.AUDIO_DTS_X to "DTS:X",
        )
    }
}
