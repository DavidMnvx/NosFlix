package fr.nosflix.app.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import fr.nosflix.app.NosFlixApp
import fr.nosflix.app.data.MediaItem
import fr.nosflix.app.data.PlaybackInfo
import fr.nosflix.app.data.Segment
import fr.nosflix.app.data.StreamLang
import fr.nosflix.app.ui.NosFlixTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

/** Lecteur vidéo intégré, basé sur VLC (libVLC), avec les options audio, sous-titres, format, vitesse et égaliseur. */
class PlayerActivity : ComponentActivity(), PlayerActions {

    private val state = PlayerUiState()
    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null
    private var itemId = ""
    private var url = ""
    private var startMs = 0L
    private var started = false
    private var ended = false
    private var progressJob: Job? = null
    /** Surveille le démarrage : une vidéo qui ne démarre pas affiche un message au lieu d'un écran noir. */
    private var watchdog: Job? = null
    private var everPlayed = false
    private var externalSubtitleTitles: List<String> = emptyList()

    /** Série : pour enchaîner sur l'épisode suivant. */
    private var seriesId: String? = null
    private var next: MediaItem? = null
    private var nextCancelled = false
    private var countdownJob: Job? = null

    /** Pistes, sous-titres et génériques connus par le serveur. */
    private var info: PlaybackInfo? = null
    private var currentSkip: Segment? = null
    private var audioApplied = false
    private var subtitlesApplied = false
    private var subtitlesWaitOver = false

    /** Première minute de lecture : a-t-elle été fluide (peu d'images perdues) ? */
    private var firstTimeMs = -1L
    private var codecChecked = false

    /**
     * Après une minute de lecture : si VLC n'a presque pas perdu d'images, ce format passe bien
     * sur cet appareil (même sans la puce vidéo) et on ne le signalera plus.
     */
    private fun confirmCodecIfSmooth() {
        val codec = info?.videoCodec ?: return
        val media = player?.media ?: return
        val stats = runCatching { media.stats }.getOrNull()
        media.release()
        if (stats == null) return
        val shown = stats.displayedPictures
        val lost = stats.lostPictures
        if (shown > 500 && lost * 20 < shown) DeviceCodecs.confirm(this, codec)
    }
    private val app: NosFlixApp get() = application as NosFlixApp
    private val prefs by lazy { getSharedPreferences("player", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        itemId = intent.getStringExtra(EXTRA_ID).orEmpty()
        url = intent.getStringExtra(EXTRA_URL).orEmpty()
        startMs = intent.getLongExtra(EXTRA_START, 0L)
        state.title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        state.subtitle = intent.getStringExtra(EXTRA_SUBTITLE).orEmpty()
        seriesId = intent.getStringExtra(EXTRA_SERIES_ID)

        val vlc = LibVLC(this, arrayListOf("--http-reconnect", "--audio-time-stretch", "--network-caching=2000"))
        val mp = MediaPlayer(vlc)
        libVlc = vlc
        player = mp
        mp.setEventListener { event -> onPlayerEvent(event) }

        val media = Media(vlc, url.toUri())
        media.setHWDecoderEnabled(true, false)
        if (startMs > 0) media.addOption(":start-time=${startMs / 1000.0}")
        mp.setMedia(media)
        media.release()

        // Égaliseur : préréglages et bandes fournis par VLC.
        state.eqPresetNames = (0 until MediaPlayer.Equalizer.getPresetCount()).map { presetLabel(MediaPlayer.Equalizer.getPresetName(it)) }
        state.eqFrequencies = (0 until MediaPlayer.Equalizer.getBandCount()).map { MediaPlayer.Equalizer.getBandFrequency(it) }
        loadSavedSettings()

        setContent {
            NosFlixTheme {
                PlayerScreen(state = state, actions = this, onAttach = ::attach)
            }
        }

        loadPlaybackInfo()
        loadNextEpisode()
    }

    private fun attach(layout: VLCVideoLayout) {
        val mp = player ?: return
        mp.attachViews(layout, null, true, false)
        mp.setVideoScale(state.format.scale)
        if (!started) {
            started = true
            applyEqualizer()
            mp.play()
            val id = itemId
            val ticks = startMs * MediaItem.TICKS_PER_MS
            app.appScope.launch { app.client.reportStart(id, ticks) }
            startProgressLoop()
            watchdog = lifecycleScope.launch {
                delay(START_TIMEOUT_MS)
                if (!everPlayed && state.error == null) {
                    state.buffering = false
                    state.error = "La vidéo ne démarre pas. Le serveur est peut-être très occupé, " +
                        "ou ce format est trop lourd pour cet appareil."
                }
            }
        }
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            while (isActive) {
                delay(10_000)
                val mp = player ?: break
                // Tant que la vidéo n'a pas démarré, on n'envoie rien (la reprise resterait à 0).
                if (!everPlayed) continue
                app.client.reportProgress(itemId, mp.time * MediaItem.TICKS_PER_MS, !mp.isPlaying)
            }
        }
    }

    /**
     * Demande au serveur les pistes (langues), les sous-titres rangés à côté de la vidéo
     * et les génériques (intro, générique de fin).
     */
    private fun loadPlaybackInfo() {
        lifecycleScope.launch {
            val loaded = runCatching { app.client.playbackInfo(itemId) }.getOrNull()
            val mp = player ?: return@launch
            info = loaded
            val subtitles = loaded?.external.orEmpty()
            subtitles.forEach { mp.addSlave(IMedia.Slave.Type.Subtitle, it.url.toUri(), false) }
            externalSubtitleTitles = subtitles.map { it.title }
            refreshTracks()
        }
    }

    /** Série : l'épisode suivant, pour le proposer pendant le générique de fin. */
    private fun loadNextEpisode() {
        val series = seriesId ?: return
        lifecycleScope.launch {
            val n = runCatching { app.client.nextEpisode(series, itemId) }.getOrNull() ?: return@launch
            next = n
            val code = listOfNotNull(n.season?.let { "S$it" }, n.episode?.let { "É$it" }).joinToString(" · ")
            state.nextLabel = listOf(code, n.name).filter { it.isNotBlank() }.joinToString(" · ")
        }
    }

    // ---------------------------------------------------------------- Langue par défaut

    /** Choisit l'audio et les sous-titres selon les préférences (une seule fois, au démarrage). */
    private fun applyLanguages() {
        val mp = player ?: return
        val i = info ?: return
        if (!everPlayed) return
        val audioTracks = mp.audioTracks.orEmpty().filter { it.id != -1 }
        if (!audioApplied && audioTracks.isNotEmpty()) {
            audioApplied = true
            fun nameOf(index: Int) = audioTracks[index].name.orEmpty() + " " + i.audio.getOrNull(index)?.title.orEmpty()
            val pick = when (LanguagePrefs.audio(this)) {
                AudioPref.Original -> null
                AudioPref.French -> audioTracks.indices.firstOrNull { LanguagePrefs.isFrench(i.audio.getOrNull(it)?.language, nameOf(it)) }
                AudioPref.English -> audioTracks.indices.firstOrNull { LanguagePrefs.isEnglish(i.audio.getOrNull(it)?.language, nameOf(it)) }
            }
            if (pick != null && audioTracks[pick].id != mp.audioTrack) mp.setAudioTrack(audioTracks[pick].id)
        }
        if (!subtitlesApplied) {
            val spu = mp.spuTracks.orEmpty().filter { it.id != -1 }
            // On attend que les sous-titres du serveur soient ajoutés (ou 4 s au plus).
            if (spu.size < i.subtitles.size + i.external.size && !subtitlesWaitOver) return
            subtitlesApplied = true
            fun streamAt(index: Int): StreamLang? = i.subtitles.getOrNull(index)
                ?: i.external.getOrNull(index - i.subtitles.size)?.let { StreamLang(it.language, it.title, it.forced, false) }
            fun nameOf(index: Int) = spu[index].name.orEmpty() + " " + streamAt(index)?.title.orEmpty()
            val french = spu.indices.filter { LanguagePrefs.isFrench(streamAt(it)?.language, nameOf(it)) }
            val frenchFull = french.firstOrNull { !LanguagePrefs.isForced(streamAt(it), nameOf(it)) }?.let { spu[it].id }
            val frenchForced = french.firstOrNull { LanguagePrefs.isForced(streamAt(it), nameOf(it)) }?.let { spu[it].id }
            val target = when (LanguagePrefs.subtitles(this)) {
                SubtitlePref.Off -> -1
                SubtitlePref.French -> frenchFull ?: frenchForced
                SubtitlePref.Auto -> if (audioIsFrench(i)) frenchForced ?: -1 else frenchFull ?: frenchForced
            }
            if (target != null && target != mp.spuTrack) mp.setSpuTrack(target)
        }
        refreshTracksOnly()
    }

    private fun audioIsFrench(i: PlaybackInfo): Boolean {
        val mp = player ?: return false
        val tracks = mp.audioTracks.orEmpty().filter { it.id != -1 }
        val index = tracks.indexOfFirst { it.id == mp.audioTrack }
        if (index < 0) return false
        return LanguagePrefs.isFrench(i.audio.getOrNull(index)?.language, tracks[index].name.orEmpty() + " " + i.audio.getOrNull(index)?.title.orEmpty())
    }

    // ---------------------------------------------------------------- Génériques et épisode suivant

    private fun updateTimed(timeMs: Long) {
        val segments = info?.segments.orEmpty()
        val skip = segments.firstOrNull { (it.type == "Intro" || it.type == "Recap") && timeMs >= it.startMs && timeMs < it.endMs - 1_000 }
        currentSkip = skip
        state.skipLabel = skip?.let { if (it.type == "Recap") "Passer le récap" else "Passer l’intro" }

        if (next == null || nextCancelled) return
        val length = state.lengthMs.takeIf { it > 0 } ?: return
        // Début du générique de fin s'il est connu, sinon 25 s avant la fin.
        val outro = segments.firstOrNull { it.type == "Outro" && it.startMs > length / 2 }?.startMs
        val trigger = outro ?: if (length > 10 * 60_000) length - 25_000 else length - 10_000
        if (timeMs >= trigger) {
            if (countdownJob == null) startCountdown()
        } else if (countdownJob != null) {
            // On est revenu en arrière : on retire la proposition.
            countdownJob?.cancel()
            countdownJob = null
            state.nextCountdown = -1
        }
    }

    private fun startCountdown() {
        // La rangée d'options rend la main : la télécommande va sur « Lecture » de l'épisode suivant.
        if (state.panel == null) state.toolbarActive = false
        state.nextCountdown = NEXT_COUNTDOWN_S
        countdownJob = lifecycleScope.launch {
            while (state.nextCountdown > 0) {
                delay(1_000)
                if (player?.isPlaying == true) state.nextCountdown--
            }
            playNextNow()
        }
    }

    override fun skipSegment() {
        val seg = currentSkip ?: return
        state.skipLabel = null
        currentSkip = null
        seekTo(seg.endMs)
    }

    override fun playNextNow() {
        val n = next ?: return
        countdownJob?.cancel()
        // L'épisode en cours compte comme vu.
        ended = true
        val url = app.client.streamUrl(n.id)
        val start = if (n.inProgress) n.positionTicks / MediaItem.TICKS_PER_MS else 0L
        val code = listOfNotNull(n.season?.let { "S$it" }, n.episode?.let { "É$it" }).joinToString(" · ")
        val subtitle = listOf(code, n.name).filter { it.isNotBlank() }.joinToString(" · ")
        startActivity(intent(this, n.id, n.displayTitle, subtitle, url, start, seriesId))
        finish()
    }

    override fun cancelNext() {
        nextCancelled = true
        countdownJob?.cancel()
        countdownJob = null
        state.nextCountdown = -1
    }

    private fun onPlayerEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Playing -> {
                state.playing = true
                state.buffering = false
                if (!everPlayed) {
                    everPlayed = true
                    // Sous-titres du serveur pas arrivés au bout de 4 s : on choisit parmi ceux qui sont là.
                    lifecycleScope.launch {
                        delay(4_000)
                        subtitlesWaitOver = true
                        applyLanguages()
                    }
                }
                watchdog?.cancel()
                refreshTracks()
            }
            MediaPlayer.Event.Paused -> {
                state.playing = false
                state.showControls()
            }
            MediaPlayer.Event.Buffering -> state.buffering = event.buffering < 100f
            MediaPlayer.Event.TimeChanged -> {
                state.timeMs = event.timeChanged
                updateTimed(event.timeChanged)
                if (firstTimeMs < 0) firstTimeMs = event.timeChanged
                if (!codecChecked && event.timeChanged - firstTimeMs >= 60_000) {
                    codecChecked = true
                    confirmCodecIfSmooth()
                }
            }
            MediaPlayer.Event.LengthChanged -> state.lengthMs = event.lengthChanged
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> refreshTracks()
            MediaPlayer.Event.EndReached -> {
                ended = true
                // Fin d'un épisode : on enchaîne sur le suivant (sauf si on a dit non).
                if (next != null && !nextCancelled) playNextNow() else finish()
            }
            MediaPlayer.Event.EncounteredError -> {
                state.buffering = false
                state.error = "Le lecteur intégré n’arrive pas à lire cette vidéo."
            }
        }
    }

    /** Relit les pistes auprès de VLC, puis applique la langue préférée si ce n'est pas encore fait. */
    private fun refreshTracks() {
        refreshTracksOnly()
        applyLanguages()
    }

    /** Relit la liste des pistes audio et sous-titres auprès de VLC. */
    private fun refreshTracksOnly() {
        val mp = player ?: return
        state.audioTracks = mp.audioTracks.orEmpty().map { TrackOption(it.id, trackLabel(it.name, "Piste ${it.id}")) }
        state.audioTrack = mp.audioTrack

        val subtitles = mp.spuTracks.orEmpty().map { TrackOption(it.id, trackLabel(it.name, "Piste ${it.id}")) }
        // Les fichiers ajoutés depuis Jellyfin arrivent en fin de liste : on leur donne leur vrai nom.
        val real = subtitles.filter { it.id != -1 }
        val externals = externalSubtitleTitles
        state.subtitleTracks = if (externals.isNotEmpty() && real.size >= externals.size) {
            val firstExternal = real.size - externals.size
            subtitles.map { track ->
                val position = real.indexOf(track)
                if (position >= firstExternal) track.copy(name = externals[position - firstExternal]) else track
            }
        } else {
            subtitles
        }
        state.subtitleTrack = mp.spuTrack
    }

    // ---------------------------------------------------------------- Réglages mémorisés

    private fun loadSavedSettings() {
        state.format = runCatching { VideoFormat.valueOf(prefs.getString(KEY_FORMAT, null) ?: VideoFormat.BestFit.name) }
            .getOrDefault(VideoFormat.BestFit)
        val bandCount = state.eqFrequencies.size
        val saved = prefs.getString(KEY_EQ_BANDS, null)?.split(',')?.mapNotNull { it.toFloatOrNull() }
        state.eqBands = if (saved != null && saved.size == bandCount) saved else List(bandCount) { 0f }
        state.eqPreamp = prefs.getFloat(KEY_EQ_PREAMP, 0f)
        state.eqPreset = prefs.getInt(KEY_EQ_PRESET, EQ_OFF)
    }

    private fun saveEqualizer() {
        prefs.edit {
            putInt(KEY_EQ_PRESET, state.eqPreset)
            putFloat(KEY_EQ_PREAMP, state.eqPreamp)
            putString(KEY_EQ_BANDS, state.eqBands.joinToString(","))
        }
    }

    private fun applyEqualizer() {
        val mp = player ?: return
        if (state.eqPreset == EQ_OFF || state.eqBands.isEmpty()) {
            mp.setEqualizer(null)
            return
        }
        val eq = MediaPlayer.Equalizer.create()
        eq.setPreAmp(state.eqPreamp)
        state.eqBands.forEachIndexed { band, db -> eq.setAmp(band, db) }
        mp.setEqualizer(eq)
    }

    // ---------------------------------------------------------------- Actions de l'interface

    override fun togglePause() {
        val mp = player ?: return
        if (mp.isPlaying) mp.pause() else mp.play()
        state.showControls()
    }

    override fun seekTo(ms: Long) {
        player?.setTime(ms)
        state.timeMs = ms
        state.showControls()
    }

    override fun close() = finish()

    override fun openExternal() {
        val position = player?.time?.takeIf { it > 0 } ?: startMs
        player?.pause()
        ExternalPlayer.open(this, url, state.title, position)
    }

    override fun openPanel(panel: PlayerPanel?) {
        if (panel != null) refreshTracks()
        state.panel = panel
        state.showControls()
    }

    override fun selectAudio(id: Int) {
        player?.setAudioTrack(id)
        refreshTracks()
    }

    override fun selectSubtitle(id: Int) {
        player?.setSpuTrack(id)
        refreshTracks()
    }

    override fun shiftAudioDelay(deltaMs: Long) {
        val value = (state.audioDelayMs + deltaMs).coerceIn(-MAX_DELAY_MS, MAX_DELAY_MS)
        player?.setAudioDelay(value * 1000)
        state.audioDelayMs = value
    }

    override fun resetAudioDelay() = shiftAudioDelay(-state.audioDelayMs)

    override fun shiftSubtitleDelay(deltaMs: Long) {
        val value = (state.subtitleDelayMs + deltaMs).coerceIn(-MAX_DELAY_MS, MAX_DELAY_MS)
        player?.setSpuDelay(value * 1000)
        state.subtitleDelayMs = value
    }

    override fun resetSubtitleDelay() = shiftSubtitleDelay(-state.subtitleDelayMs)

    override fun setFormat(format: VideoFormat) {
        player?.setVideoScale(format.scale)
        state.format = format
        prefs.edit { putString(KEY_FORMAT, format.name) }
    }

    override fun setRate(rate: Float) {
        player?.setRate(rate)
        state.rate = rate
    }

    override fun setEqPreset(index: Int) {
        if (index == EQ_OFF) {
            state.eqPreset = EQ_OFF
        } else {
            val eq = MediaPlayer.Equalizer.createFromPreset(index)
            state.eqPreamp = eq.preAmp
            state.eqBands = List(state.eqFrequencies.size) { eq.getAmp(it) }
            state.eqPreset = index
        }
        applyEqualizer()
        saveEqualizer()
    }

    override fun shiftEqBand(band: Int, deltaDb: Float) {
        if (band !in state.eqBands.indices) return
        state.eqBands = state.eqBands.toMutableList().also { it[band] = (it[band] + deltaDb).coerceIn(-20f, 20f) }
        state.eqPreset = EQ_CUSTOM
        applyEqualizer()
        saveEqualizer()
    }

    override fun shiftEqPreamp(deltaDb: Float) {
        state.eqPreamp = (state.eqPreamp + deltaDb).coerceIn(-20f, 20f)
        if (state.eqPreset == EQ_OFF) state.eqPreset = EQ_CUSTOM
        applyEqualizer()
        saveEqualizer()
    }

    private fun seekBy(deltaMs: Long) {
        val mp = player ?: return
        val length = mp.length
        var target = (mp.time + deltaMs).coerceAtLeast(0L)
        if (length > 0) target = target.coerceAtMost(length - 1000)
        mp.setTime(target)
        state.timeMs = target
        state.showControls()
    }

    // ---------------------------------------------------------------- Télécommande

    /** Clavier (émulateur, clavier Bluetooth) : Échap = bouton Retour de la télécommande. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP) onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Télécommande, quand aucun bouton n'a le focus :
     * OK = pause/lecture, ← −10 s, → +30 s, ↑ affiche les commandes, ↓ (ou Menu) ouvre les options.
     * Quand les options sont ouvertes, les flèches servent à naviguer dedans.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (player == null || state.error != null) return super.onKeyDown(keyCode, event)

        if (state.toolbarActive || state.panel != null) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    // Flèche haut depuis la rangée de boutons : on revient au mode avance/recul.
                    if (state.panel == null) state.toolbarActive = false
                    state.showControls()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    togglePause()
                    return true
                }
            }
            return super.onKeyDown(keyCode, event)
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            -> {
                togglePause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                player?.play()
                state.showControls()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                player?.pause()
                state.showControls()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekBy(-10_000)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekBy(30_000)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> {
                state.showControls()
                state.toolbarActive = true
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                state.showControls()
                return true
            }
            KeyEvent.KEYCODE_CAPTIONS -> {
                openPanel(PlayerPanel.Subtitles)
                state.toolbarActive = true
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing) player?.pause()
    }

    override fun onDestroy() {
        progressJob?.cancel()
        watchdog?.cancel()
        val mp = player
        val vlc = libVlc
        player = null
        libVlc = null
        if (mp != null) {
            val positionMs = if (ended) maxOf(mp.length, state.lengthMs) else mp.time.coerceAtLeast(0)
            val id = itemId
            // Une vidéo qui n'a jamais démarré ne doit pas effacer la position enregistrée.
            if (everPlayed || ended) app.appScope.launch { app.client.reportStopped(id, positionMs * MediaItem.TICKS_PER_MS) }
            mp.setEventListener(null)
            mp.detachViews()
        }
        // VLC peut mettre longtemps à s'arrêter (vidéo bloquée en chargement, décodage trop lourd) :
        // on le libère à part pour que l'appli ne reste pas figée en sortant du lecteur.
        Thread({
            runCatching {
                mp?.stop()
                mp?.release()
                vlc?.release()
            }
        }, "vlc-release").start()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUBTITLE = "subtitle"
        private const val EXTRA_URL = "url"
        private const val EXTRA_START = "startMs"
        private const val EXTRA_SERIES_ID = "seriesId"
        private const val NEXT_COUNTDOWN_S = 10

        private const val KEY_FORMAT = "format"
        private const val KEY_EQ_PRESET = "eqPreset"
        private const val KEY_EQ_PREAMP = "eqPreamp"
        private const val KEY_EQ_BANDS = "eqBands"
        private const val MAX_DELAY_MS = 10_000L
        private const val START_TIMEOUT_MS = 30_000L

        fun intent(context: Context, itemId: String, title: String, subtitle: String, url: String, startMs: Long, seriesId: String? = null): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_ID, itemId)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_SUBTITLE, subtitle)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_START, startMs)
                .putExtra(EXTRA_SERIES_ID, seriesId)
    }
}
