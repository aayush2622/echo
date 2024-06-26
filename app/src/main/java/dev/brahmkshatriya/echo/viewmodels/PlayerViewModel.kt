package dev.brahmkshatriya.echo.viewmodels

import android.app.Application
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaBrowser
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.brahmkshatriya.echo.common.clients.LibraryClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.TrackerClient
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.StreamableAudio
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.di.ExtensionModule
import dev.brahmkshatriya.echo.di.TrackerModule
import dev.brahmkshatriya.echo.playback.PlayerListener
import dev.brahmkshatriya.echo.playback.Queue
import dev.brahmkshatriya.echo.playback.RadioListener.Companion.radio
import dev.brahmkshatriya.echo.ui.player.CheckBoxListener
import dev.brahmkshatriya.echo.utils.observe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val global: Queue,
    private val messageFlow: MutableSharedFlow<SnackBar.Message>,
    private val app: Application,
    private val trackerListFlow: TrackerModule.TrackerListFlow,
    val extensionListFlow: ExtensionModule.ExtensionListFlow,
    throwableFlow: MutableSharedFlow<Throwable>
) : CatchingViewModel(throwableFlow) {


    val playPause: MutableSharedFlow<Boolean> = MutableSharedFlow()
    val playPauseListener = CheckBoxListener {
        viewModelScope.launch { playPause.emit(it) }
    }

    val shuffle = MutableStateFlow(false)
    val shuffleListener = CheckBoxListener {
        viewModelScope.launch { shuffle.emit(it) }
    }

    val repeat = MutableStateFlow(0)
    var repeatEnabled = false
    fun onRepeat(it: Int) {
        if (repeatEnabled) viewModelScope.launch { repeat.emit(it) }
    }

    val seekTo: MutableSharedFlow<Long> = MutableSharedFlow()

    val seekToPrevious: MutableSharedFlow<Unit> = MutableSharedFlow()
    val seekToNext: MutableSharedFlow<Unit> = MutableSharedFlow()

    val clearQueueFlow = global.clearQueueFlow

    fun clearQueue() {
        viewModelScope.launch {
            global.clearQueue()
        }
    }

    val moveTrackFlow = global.moveTrackFlow
    fun moveQueueItems(new: Int, old: Int) {
        viewModelScope.launch {
            global.moveTrack(old, new)
        }
    }

    val removeTrackFlow = global.removeTrackFlow
    fun removeQueueItem(index: Int) {
        viewModelScope.launch {
            global.removeTrack(index)
        }
    }

    val addTrackFlow = global.addTrackFlow
    val audioIndexFlow = MutableSharedFlow<Int>()
    fun play(clientId: String, track: Track, playIndex: Int? = null) =
        play(clientId, listOf(track), playIndex)


    fun play(clientId: String, tracks: List<Track>, playIndex: Int? = null) {
        viewModelScope.launch {
            val pos = global.addTracks(clientId, tracks).first
            playIndex?.let { audioIndexFlow.emit(pos + it) }
        }
    }

    fun addToQueue(clientId: String, track: Track, end: Boolean) =
        addToQueue(clientId, listOf(track), end)

    fun addToQueue(clientId: String, tracks: List<Track>, end:Boolean) {
        viewModelScope.launch {
            val index = if (end) global.queue.size else 0
            global.addTracks(clientId, tracks, index)
        }
    }

    private fun playRadio(clientId: String, block: suspend RadioClient.() -> Playlist) {
        val client = extensionListFlow.getClient(clientId)
        viewModelScope.launch(Dispatchers.IO) {
            val position = radio(app, client, messageFlow, global) { tryWith { block(this) } }
            position?.let { audioIndexFlow.emit(it) }
        }
    }

    fun likeTrack(streamableTrack: Queue.StreamableTrack, isLiked: Boolean) {
        if (streamableTrack.liked != isLiked) viewModelScope.launch(Dispatchers.IO) {
            streamableTrack.run {
                val client = extensionListFlow.getClient(clientId)
                if (client !is LibraryClient) return@run
                val track = loaded ?: unloaded
                val response = tryWith { client.likeTrack(track, isLiked) }
                liked = response ?: liked
                onLiked.emit(liked)
            }
        }
    }

    fun radio(clientId: String, track: Track) = playRadio(clientId) { radio(track) }
    fun radio(clientId: String, album: Album) = playRadio(clientId) { radio(album) }
    fun radio(clientId: String, artist: Artist) = playRadio(clientId) { radio(artist) }
    fun radio(clientId: String, playlist: Playlist) = playRadio(clientId) { radio(playlist) }


    companion object {
        fun LifecycleOwner.connectPlayerToUI(player: MediaBrowser, viewModel: PlayerViewModel) {
            val listener = PlayerListener(player, viewModel)
            player.addListener(listener)

            observe(viewModel.playPause) {
                if (it) player.play() else player.pause()
            }
            observe(viewModel.seekToPrevious) {
                player.seekToPrevious()
                player.playWhenReady = true
            }
            observe(viewModel.seekToNext) {
                player.seekToNext()
                player.playWhenReady = true
            }
            observe(viewModel.audioIndexFlow) {
                if (it >= 0) player.seekToDefaultPosition(it)
            }
            observe(viewModel.seekTo) {
                player.seekTo(it)
            }
            observe(viewModel.repeat) {
                player.repeatMode = it
            }
            observe(viewModel.shuffle) {
                player.shuffleModeEnabled = it
            }
            observe(viewModel.addTrackFlow) { (index, item) ->
                player.addMediaItems(index, item)
                player.prepare()
                player.playWhenReady = true
            }
            observe(viewModel.moveTrackFlow) { (new, old) ->
                player.moveMediaItem(old, new)
            }
            observe(viewModel.removeTrackFlow) {
                player.removeMediaItem(it)
            }
            observe(viewModel.clearQueueFlow) {
                if (player.mediaItemCount == 0) return@observe
                player.pause()
                player.clearMediaItems()
                player.stop()
            }
        }
    }

    fun createException(exception: PlaybackException) {
        viewModelScope.launch {
            val streamableTrack = global.current
            val currentAudio = global.currentAudioFlow.value
            throwableFlow.emit(
                PlayerException(exception.cause ?: exception, streamableTrack, currentAudio)
            )
        }
    }

    data class PlayerException(
        override val cause: Throwable,
        val streamableTrack: Queue.StreamableTrack?,
        val currentAudio: StreamableAudio?
    ) : Throwable(cause.message)

    fun updateList(mediaItems: List<String>, index: Int) {
        global.updateQueue(mediaItems)
        viewModelScope.launch {
            global.currentIndexFlow.value = index
            _updatedFlow.emit(Unit)
        }
    }

    private fun trackMedia(
        mediaId: String?,
        block: suspend TrackerClient.(clientId: String, track: Track) -> Unit
    ) {
        val streamableTrack = global.getTrack(mediaId) ?: return
        val client = extensionListFlow.getClient(streamableTrack.clientId) ?: return
        val track = streamableTrack.loaded ?: streamableTrack.unloaded
        val clientId = client.metadata.id
        val trackers = trackerListFlow.list ?: emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            if (client is TrackerClient) tryWith {
                client.block(clientId, track)
            }
            trackers.map {
                launch {
                    tryWith { it.block(clientId, track) }
                }
            }
        }
    }

    fun markedAsPlayed(mediaId: String?) {
        trackMedia(mediaId) { clientId, loaded ->
            onMarkAsPlayed(clientId, loaded)
        }
    }

    fun startedPlaying(mediaId: String?) {
        trackMedia(mediaId) { clientId, loaded ->
            onStartedPlaying(clientId, loaded)
        }
    }


    val current get() = global.current
    val currentIndex get() = global.currentIndexFlow.value

    val currentFlow = global.currentIndexFlow.map { current }
    private val _updatedFlow = MutableSharedFlow<Unit>()
    val listChangeFlow = merge(MutableStateFlow(Unit), _updatedFlow).map { global.queue }

    val progress = MutableStateFlow(0 to 0)
    val totalDuration = MutableStateFlow(0)

    val buffering = MutableStateFlow(false)
    val isPlaying = MutableStateFlow(false)
    val nextEnabled = MutableStateFlow(false)
    val previousEnabled = MutableStateFlow(false)
}