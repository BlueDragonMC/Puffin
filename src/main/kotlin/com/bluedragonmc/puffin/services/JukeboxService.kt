package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.JukeboxGrpcKt
import com.bluedragonmc.api.grpc.JukeboxOuterClass
import com.bluedragonmc.puffin.util.Utils.handleRPC
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.inject.Inject
import com.google.inject.Singleton
import com.google.protobuf.Empty
import java.time.Duration
import java.util.*

/**
 * Saves current song information while players transfer between servers.
 */
@Singleton
class JukeboxService @Inject constructor(val playerTracker: IPlayerTracker) : Service() {

    inner class JukeboxRedirectService : JukeboxGrpcKt.JukeboxCoroutineImplBase() {

        private fun stubTo(playerUUID: String): JukeboxGrpcKt.JukeboxCoroutineStub? {
            return playerTracker.getChannelToPlayer(UUID.fromString(playerUUID))?.let {
                JukeboxGrpcKt.JukeboxCoroutineStub(it)
            }
        }

        private val songCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(6))
            .expireAfterAccess(Duration.ofHours(6))
            .build<UUID, JukeboxOuterClass.PlayerSongQueue>()

        override suspend fun getSongQueue(request: JukeboxOuterClass.GetSongQueueRequest): JukeboxOuterClass.PlayerSongQueue =
            handleRPC {
                return songCache.getIfPresent(UUID.fromString(request.playerUuid))
                    ?: JukeboxOuterClass.PlayerSongQueue.getDefaultInstance()
            }

        override suspend fun setSongQueue(request: JukeboxOuterClass.SetSongQueueRequest): Empty = handleRPC {
            val player = UUID.fromString(request.playerUuid)
            songCache.put(player, request.queue)
            stubTo(request.playerUuid)?.setSongQueue(request) // Inform the player's server that their song queue has changed
            return Empty.getDefaultInstance()
        }
    }
}
