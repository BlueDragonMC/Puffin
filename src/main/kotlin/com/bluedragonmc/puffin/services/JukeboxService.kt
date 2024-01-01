package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.JukeboxGrpcKt
import com.bluedragonmc.api.grpc.JukeboxOuterClass
import com.bluedragonmc.puffin.util.Utils
import com.bluedragonmc.puffin.util.Utils.handleRPC
import com.google.protobuf.Empty
import java.util.*

/**
 * Redirects Jukebox requests to the player's proxy.
 */
class JukeboxService(app: ServiceHolder) : Service(app) {

    inner class JukeboxRedirectService : JukeboxGrpcKt.JukeboxCoroutineImplBase() {

        private fun stubTo(playerUUID: String): JukeboxGrpcKt.JukeboxCoroutineStub {
            return JukeboxGrpcKt.JukeboxCoroutineStub(
                Utils.getChannelToProxyOf(UUID.fromString(playerUUID))!!
            )
        }

        override suspend fun getSongInfo(request: JukeboxOuterClass.SongInfoRequest) = handleRPC {
            stubTo(request.playerUuid).getSongInfo(request)
        }

        override suspend fun playSong(request: JukeboxOuterClass.PlaySongRequest) = handleRPC {
            stubTo(request.playerUuid).playSong(request)
        }

        override suspend fun removeSong(request: JukeboxOuterClass.SongRemoveRequest) = handleRPC {
            stubTo(request.playerUuid).removeSong(request)
        }

        override suspend fun removeSongs(request: JukeboxOuterClass.BatchSongRemoveRequest) = handleRPC {
            stubTo(request.playerUuid).removeSongs(request)
        }

        override suspend fun stopSong(request: JukeboxOuterClass.StopSongRequest) = handleRPC {
            stubTo(request.playerUuid).stopSong(request)
        }
    }
}