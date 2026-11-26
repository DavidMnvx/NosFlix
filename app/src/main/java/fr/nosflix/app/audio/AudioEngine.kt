package fr.nosflix.app.audio

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import fr.nosflix.app.NosFlixApp
import fr.nosflix.app.data.AudioApi
import fr.nosflix.app.data.AudioBook
import fr.nosflix.app.data.AudioChapter
import fr.nosflix.app.data.AudioTrack
import fr.nosflix.app.data.MediaItem as JfItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class AudioKind { Music, Book }

enum class RepeatMode { Off, All, One }

/** Un chapitre affiché dans le lecteur : une partie (fichier) ou un chapitre intégré au fichier. */
data class ChapterEntry(val title: String, val partIndex: Int, val startMs: Long, val durationMs: Long)

/**
 * Le lecteur audio de l'appli (musique et livres audio), partagé par tous les écrans.
 * Le service [AudioService] le relie à la notification et à l'écran de verrouillage.
 * Tout se passe sur le fil principal.
 */
class AudioEngine(private val app: NosFlixApp) {

    private val api: AudioApi get() = app.audioApi
    private val prefs = app.getSharedPreferences("audio", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var exo: ExoPlayer? = null
    private var controller: ListenableFuture<MediaController>? = null

    // ------------------------------------------------------------ État lu par l'interface
    var queue by mutableStateOf<List<AudioTrack>>(emptyList())
        private set
    var index by mutableIntStateOf(-1)
        private set
    var playing by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var shuffle by mutableStateOf(false)
        private set
    var repeat by mutableStateOf(RepeatMode.Off)
        private set
    var speed by mutableFloatStateOf(1f)
        private set
    var kind by mutableStateOf(AudioKind.Music)
        private set
    /** « Album · Titre », « Playlist · Nom »… (affiché en haut du lecteur). */
    var source by mutableStateOf("")
        private set
    var book by mutableStateOf<AudioBook?>(null)
        private set
    /** Minuterie : heure d'arrêt (horloge système), ou arrêt à la fin du chapitre. */
    var sleepAt by mutableStateOf<Long?>(null)
        private set
    var sleepEndOfChapter by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val current: AudioTrack? get() = queue.getOrNull(index)
    val active: Boolean get() = queue.isNotEmpty()

    /** Chapitres du livre en cours (vide en musique). */
    val chapters: List<ChapterEntry>
        get() {
            val b = book ?: return emptyList()
            if (kind != AudioKind.Book) return emptyList()
            return chaptersOf(b)
        }

    /** Chapitre en cours de lecture (indice dans [chapters]). */
    val chapterIndex: Int
        get() {
            val list = chapters
            if (list.isEmpty()) return -1
            return list.indexOfLast { it.partIndex < index || (it.partIndex == index && it.startMs <= positionMs + 500) }.coerceAtLeast(0)
        }

    // ------------------------------------------------------------ Commandes

    /** Lance une liste de morceaux (album, playlist…) à partir de [start]. */
    fun playTracks(tracks: List<AudioTrack>, start: Int = 0, shuffled: Boolean = false, sourceLabel: String = "") {
        if (tracks.isEmpty()) return
        val p = player()
        reportStop()
        kind = AudioKind.Music
        book = null
        source = sourceLabel
        clearSleep()
        loadQueue(tracks)
        p.setAudioAttributes(attributes(C.AUDIO_CONTENT_TYPE_MUSIC), true)
        p.setPlaybackSpeed(1f)
        speed = 1f
        p.shuffleModeEnabled = shuffled
        shuffle = shuffled
        val first = if (shuffled && start == 0) tracks.indices.random() else start.coerceIn(tracks.indices)
        p.setMediaItems(tracks.map(::toMediaItem), first, 0L)
        p.prepare()
        p.play()
        onStarted()
    }

    /** Lance ou reprend un livre audio. */
    fun playBook(b: AudioBook, part: Int, startMs: Long) {
        if (b.parts.isEmpty()) return
        val p = player()
        if (kind == AudioKind.Book && book?.id == b.id && queue.size == b.parts.size) {
            // Même livre déjà chargé : on se déplace simplement.
            p.seekTo(part.coerceIn(b.parts.indices), startMs)
            p.play()
            return
        }
        reportStop()
        kind = AudioKind.Book
        book = b
        source = b.title
        clearSleep()
        loadQueue(b.parts)
        p.setAudioAttributes(attributes(C.AUDIO_CONTENT_TYPE_SPEECH), true)
        p.shuffleModeEnabled = false
        shuffle = false
        p.repeatMode = Player.REPEAT_MODE_OFF
        repeat = RepeatMode.Off
        val bookSpeed = prefs.getFloat(KEY_BOOK_SPEED, 1f)
        p.setPlaybackSpeed(bookSpeed)
        speed = bookSpeed
        p.setMediaItems(b.parts.map(::toMediaItem), part.coerceIn(b.parts.indices), startMs.coerceAtLeast(0))
        p.prepare()
        p.play()
        onStarted()
    }

    fun toggle() {
        val p = exo ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0, 0)
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
        }
    }

    fun pause() {
        exo?.pause()
    }

    fun next() {
        val p = exo ?: return
        if (kind == AudioKind.Book) {
            val list = chapters
            val i = chapterIndex
            if (i in 0 until list.size - 1) seekChapter(i + 1)
            return
        }
        if (p.hasNextMediaItem()) p.seekToNextMediaItem()
    }

    fun previous() {
        val p = exo ?: return
        if (kind == AudioKind.Book) {
            val list = chapters
            val i = chapterIndex
            if (i < 0) return
            val inChapter = positionMs - list[i].startMs
            seekChapter(if (inChapter > 3_000 || i == 0) i else i - 1)
            return
        }
        if (p.currentPosition > 3_000 || !p.hasPreviousMediaItem()) p.seekTo(0) else p.seekToPreviousMediaItem()
    }

    fun seekTo(ms: Long) {
        val p = exo ?: return
        p.seekTo(ms.coerceAtLeast(0))
        positionMs = ms.coerceAtLeast(0)
    }

    /** −30 s / +30 s (livres audio). */
    fun seekBy(deltaMs: Long) {
        val p = exo ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        val target = p.currentPosition + deltaMs
        when {
            target < 0 && p.hasPreviousMediaItem() && kind == AudioKind.Book -> {
                val prevIndex = p.previousMediaItemIndex
                val prevDur = queue.getOrNull(prevIndex)?.durationMs ?: 0L
                p.seekTo(prevIndex, (prevDur + target).coerceAtLeast(0))
            }
            target > dur && p.hasNextMediaItem() -> p.seekTo(p.nextMediaItemIndex, (target - dur).coerceAtLeast(0))
            else -> p.seekTo(target.coerceIn(0, dur))
        }
    }

    fun seekChapter(i: Int) {
        val list = chapters
        val c = list.getOrNull(i) ?: return
        val p = exo ?: return
        p.seekTo(c.partIndex, c.startMs)
        positionMs = c.startMs
        if (!p.isPlaying) p.play()
    }

    fun jumpTo(queueIndex: Int) {
        val p = exo ?: return
        if (queueIndex !in queue.indices) return
        p.seekTo(queueIndex, 0)
        if (!p.isPlaying) p.play()
    }

    fun toggleShuffle() {
        val p = exo ?: return
        p.shuffleModeEnabled = !p.shuffleModeEnabled
    }

    fun cycleRepeat() {
        val p = exo ?: return
        p.repeatMode = when (p.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun setPlaybackSpeed(value: Float) {
        val p = exo ?: return
        p.setPlaybackSpeed(value)
        speed = value
        if (kind == AudioKind.Book) prefs.edit { putFloat(KEY_BOOK_SPEED, value) }
    }

    /** Minuterie : [minutes] > 0, 0 = désactivée, -1 = à la fin du chapitre. */
    fun setSleep(minutes: Int) {
        exo?.volume = 1f
        when {
            minutes > 0 -> {
                sleepAt = System.currentTimeMillis() + minutes * 60_000L
                sleepEndOfChapter = false
            }
            minutes < 0 -> {
                sleepAt = null
                sleepEndOfChapter = true
            }
            else -> clearSleep()
        }
    }

    /** « Lire ensuite » : après le morceau en cours. */
    fun playNext(tracks: List<AudioTrack>) {
        if (tracks.isEmpty()) return
        if (!active || kind != AudioKind.Music) {
            playTracks(tracks)
            return
        }
        val p = player()
        val at = (index + 1).coerceIn(0, queue.size)
        p.addMediaItems(at, tracks.map(::toMediaItem))
        queue = queue.toMutableList().apply { addAll(at, tracks) }
    }

    /** « Ajouter à la file d'attente ». */
    fun enqueue(tracks: List<AudioTrack>) {
        if (tracks.isEmpty()) return
        if (!active || kind != AudioKind.Music) {
            playTracks(tracks)
            return
        }
        val p = player()
        p.addMediaItems(tracks.map(::toMediaItem))
        queue = queue + tracks
    }

    /** Ce qui va suivre, dans l'ordre réel (aléatoire compris) : (indice dans la file, morceau). */
    fun upNext(limit: Int = 100): List<Pair<Int, AudioTrack>> {
        val p = exo ?: return emptyList()
        val timeline = p.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val out = mutableListOf<Pair<Int, AudioTrack>>()
        var i = p.currentMediaItemIndex
        val seen = HashSet<Int>()
        while (out.size < limit) {
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
            if (i == C.INDEX_UNSET || !seen.add(i)) break
            queue.getOrNull(i)?.let { out += i to it }
        }
        return out
    }

    /** Coupe tout (déconnexion, vidéo lancée…). */
    fun stop() {
        val p = exo ?: return
        reportStop()
        p.stop()
        p.clearMediaItems()
        queue = emptyList()
        index = -1
        book = null
        playing = false
        clearSleep()
    }

    fun chaptersOf(b: AudioBook): List<ChapterEntry> {
        if (b.parts.size > 1) {
            return b.parts.mapIndexed { i, part -> ChapterEntry(part.title, i, 0L, part.durationMs) }
        }
        val part = b.parts.firstOrNull() ?: return emptyList()
        val list: List<AudioChapter> = part.chapters
        if (list.isEmpty()) return listOf(ChapterEntry(part.title.ifBlank { b.title }, 0, 0L, part.durationMs))
        return list.mapIndexed { i, c ->
            val end = list.getOrNull(i + 1)?.startMs ?: part.durationMs
            ChapterEntry(c.title, 0, c.startMs, (end - c.startMs).coerceAtLeast(0))
        }
    }

    // ------------------------------------------------------------ Lecteur

    private fun player(): ExoPlayer {
        exo?.let { return it }
        val p = ExoPlayer.Builder(app)
            .setAudioAttributes(attributes(C.AUDIO_CONTENT_TYPE_MUSIC), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(30_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        p.addListener(listener)
        exo = p
        return p
    }

    /** Le lecteur, pour le service (notification). Créé au besoin. */
    internal fun sessionPlayer(): ExoPlayer = player()

    private fun attributes(contentType: Int) = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(contentType)
        .build()

    private fun loadQueue(tracks: List<AudioTrack>) {
        // (la fin du morceau précédent est envoyée avant de changer de livre ou de musique)
        fallbacks.clear()
        queue = tracks
        error = null
    }

    private fun toMediaItem(track: AudioTrack): MediaItem {
        val b = book
        val image = (if (kind == AudioKind.Book) b?.imageItemId else null) ?: track.imageItemId
        val meta = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(if (kind == AudioKind.Book) b?.author ?: track.artist else track.artist)
            .setAlbumTitle(if (kind == AudioKind.Book) b?.title else track.album)
            .setArtworkUri(api.imageUrl(image, 512)?.let(Uri::parse))
            .setMediaType(if (kind == AudioKind.Book) MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER else MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(api.streamUrl(track))
            .setMediaMetadata(meta)
            .build()
    }

    /** Branche le service (notification, écran de verrouillage, lecture écran éteint). */
    private fun onStarted() {
        error = null
        if (controller == null) {
            val token = SessionToken(app, ComponentName(app, AudioService::class.java))
            controller = MediaController.Builder(app, token).buildAsync()
        }
        startTicker()
    }

    // ------------------------------------------------------------ Suivi (Jellyfin, minuterie, position)

    private var ticker: Job? = null
    private var reportedId: String? = null
    private var lastPositionMs = 0L
    private var lastDurationMs = 0L
    private var lastReportAt = 0L
    private val fallbacks = HashSet<String>()

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                tick()
                delay(if (playing) 250 else 1_000)
            }
        }
    }

    private fun tick() {
        val p = exo ?: return
        positionMs = p.currentPosition.coerceAtLeast(0)
        durationMs = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: current?.durationMs ?: 0L
        lastPositionMs = positionMs
        lastDurationMs = durationMs
        val now = System.currentTimeMillis()
        if (playing && now - lastReportAt > 10_000) {
            lastReportAt = now
            reportedId?.let { id -> report { app.client.reportProgress(id, ticks(positionMs), paused = false) } }
        }
        // Minuterie, avec un fondu sur les 8 dernières secondes.
        sleepAt?.let { end ->
            val left = end - now
            when {
                left <= 0 -> {
                    p.pause()
                    p.volume = 1f
                    clearSleep()
                }
                left < 8_000 && playing -> p.volume = (left / 8_000f).coerceIn(0.05f, 1f)
            }
        }
        if (sleepEndOfChapter && playing && kind == AudioKind.Book) {
            val list = chapters
            val c = list.getOrNull(chapterIndex)
            if (c != null && c.partIndex == index && list.size > 1 && positionMs >= c.startMs + c.durationMs - 300 && c.durationMs > 0) {
                p.pause()
                clearSleep()
            }
        }
    }

    private fun clearSleep() {
        sleepAt = null
        sleepEndOfChapter = false
        exo?.volume = 1f
    }

    private fun ticks(ms: Long) = ms * JfItem.TICKS_PER_MS

    private fun report(block: suspend () -> Unit) {
        app.appScope.launch { runCatching { block() } }
        saveBookPoint()
    }

    // ------------------------------------------------------------ Position des livres, gardée sur l'appareil
    // Jellyfin oublie la position d'un fichier de livre audio écouté moins de 5 minutes :
    // on la garde aussi ici, à la seconde près.

    /** Où tu en étais dans ce livre d'après l'appareil : (partie, position, heure de l'enregistrement). */
    fun savedPoint(bookId: String): Triple<Int, Long, Long>? {
        val raw = prefs.getString(KEY_BOOK_POINT + bookId, null) ?: return null
        val bits = raw.split('|')
        if (bits.size != 3) return null
        return Triple(bits[0].toIntOrNull() ?: return null, bits[1].toLongOrNull() ?: return null, bits[2].toLongOrNull() ?: return null)
    }

    private fun saveBookPoint(finished: Boolean = false) {
        val b = book ?: return
        if (kind != AudioKind.Book || index < 0) return
        prefs.edit {
            if (finished) remove(KEY_BOOK_POINT + b.id) else putString(KEY_BOOK_POINT + b.id, "$index|$lastPositionMs|${System.currentTimeMillis()}")
        }
    }

    private fun reportStart(id: String, positionMs: Long) {
        reportedId = id
        lastReportAt = System.currentTimeMillis()
        report { app.client.reportStart(id, ticks(positionMs)) }
    }

    private fun reportStop(finished: Boolean = false) {
        val id = reportedId ?: return
        reportedId = null
        val pos = if (finished) lastDurationMs.coerceAtLeast(lastPositionMs) else lastPositionMs
        report { app.client.reportStopped(id, ticks(pos)) }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playing = isPlaying
            if (isPlaying) {
                startTicker()
                val id = exo?.currentMediaItem?.mediaId
                if (id != null && reportedId != id) reportStart(id, exo?.currentPosition ?: 0L)
            } else {
                tick()
                reportedId?.let { id -> report { app.client.reportProgress(id, ticks(lastPositionMs), paused = true) } }
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            buffering = state == Player.STATE_BUFFERING
            if (state == Player.STATE_ENDED) {
                reportStop(finished = true)
                playing = false
                // Livre fini : on oublie la position gardée sur l'appareil.
                if (kind == AudioKind.Book) saveBookPoint(finished = true)
            }
            tick()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val p = exo ?: return
            val previousIndex = index
            index = p.currentMediaItemIndex
            val auto = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
            reportStop(finished = auto)
            positionMs = p.currentPosition
            durationMs = current?.durationMs ?: 0L
            lastPositionMs = positionMs
            lastDurationMs = durationMs
            val id = mediaItem?.mediaId
            if (id != null && p.playWhenReady) reportStart(id, p.currentPosition)
            // Livre : « fin du chapitre » = fin du fichier quand chaque fichier est un chapitre.
            if (auto && sleepEndOfChapter && kind == AudioKind.Book && previousIndex >= 0 && (book?.parts?.size ?: 0) > 1) {
                p.pause()
                clearSleep()
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            shuffle = shuffleModeEnabled
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            repeat = when (repeatMode) {
                Player.REPEAT_MODE_ALL -> RepeatMode.All
                Player.REPEAT_MODE_ONE -> RepeatMode.One
                else -> RepeatMode.Off
            }
        }

        override fun onPlayerError(e: PlaybackException) {
            val p = exo ?: return
            val track = current
            // Format non pris en charge par le téléphone : on redemande le morceau converti en mp3.
            if (track != null && fallbacks.add(track.id)) {
                val position = p.currentPosition
                p.replaceMediaItem(index, toMediaItem(track).buildUpon().setUri(api.streamUrl(track, forceTranscode = true)).build())
                p.seekTo(index, position)
                p.prepare()
                p.play()
                return
            }
            error = "Lecture impossible : " + (e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' '))
            if (p.hasNextMediaItem()) {
                p.seekToNextMediaItem()
                p.prepare()
                p.play()
            }
        }
    }

    private companion object {
        const val KEY_BOOK_SPEED = "bookSpeed"
        const val KEY_BOOK_POINT = "book:"
    }
}
