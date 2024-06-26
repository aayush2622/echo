package dev.brahmkshatriya.echo.playback

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource.Resolver
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.StreamableAudio
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.di.ExtensionModule
import dev.brahmkshatriya.echo.ui.settings.AudioFragment
import dev.brahmkshatriya.echo.utils.getFromCache
import dev.brahmkshatriya.echo.utils.saveToCache
import dev.brahmkshatriya.echo.viewmodels.ExtensionViewModel.Companion.noClient
import dev.brahmkshatriya.echo.viewmodels.ExtensionViewModel.Companion.trackNotSupported
import kotlinx.coroutines.runBlocking

class TrackResolver(
    private val context: Context,
    private val global: Queue,
    private val extensionListFlow: ExtensionModule.ExtensionListFlow,
    private val settings: SharedPreferences
) : Resolver {

    @UnstableApi
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val id = dataSpec.uri.toString()
        val streamable = dataSpec.customData as? StreamableAudio ?: run {
            val track = global.getTrack(id)
                ?: throw Exception(context.getString(R.string.track_not_found))

            val client = extensionListFlow.getClient(track.clientId)
                ?: throw Exception(context.noClient().message)

            if (client !is TrackClient)
                throw Exception(context.trackNotSupported(client.metadata.name).message)

            val loadedTrack = getTrack(track, client)
            loadAudio(loadedTrack, client)
        }
        global.currentAudioFlow.value = streamable
        return dataSpec.copy(customData = streamable)
    }

    private fun getTrack(
        streamableTrack: Queue.StreamableTrack, client: TrackClient
    ): Track {
        val track = streamableTrack.loaded ?: loadTrack(client, streamableTrack)
        current = track
        return track
    }


    private fun loadTrack(client: TrackClient, streamableTrack: Queue.StreamableTrack): Track {
        val id = streamableTrack.unloaded.id
        val track = getTrackFromCache(id) ?: runBlocking {
            runCatching { client.loadTrack(streamableTrack.unloaded) }
        }.getOrThrow()
        context.saveToCache(id, track)
        if (streamableTrack.loaded == null) streamableTrack.run {
            loaded = track
            liked = track.liked
            runBlocking {
                onLoad.emit(track)
                onLiked.emit(track.liked)
            }
        }
        return track
    }

    private fun loadAudio(track: Track, client: TrackClient): StreamableAudio {
        val streamable = selectStream(track.audioStreamables)
            ?: throw Exception(context.getString(R.string.no_streams_found))
        return runBlocking {
            runCatching { client.getStreamableAudio(streamable) }
        }.getOrThrow()
    }

    private fun selectStream(streamables: List<Streamable>) =
        when (settings.getString(AudioFragment.AudioPreference.STREAM_QUALITY, "lowest")) {
            "highest" -> streamables.maxByOrNull { it.quality }
            "medium" -> streamables.sortedBy { it.quality }.getOrNull(streamables.size / 2)
            "lowest" -> streamables.minByOrNull { it.quality }
            else -> streamables.firstOrNull()
        }

    private var current: Track? = null
    private fun getTrackFromCache(id: String): Track? {
        val track = current?.takeIf { it.id == id }
            ?: context.getFromCache(id) { Track.creator.createFromParcel(it) } ?: return null
        return if (!track.isExpired()) track else null
    }

    private fun Track.isExpired() = System.currentTimeMillis() > expiresAt
}