package com.bluedragonmc.puffin.grpc

import com.bluedragonmc.api.grpc.Map
import com.bluedragonmc.api.grpc.MapServiceGrpcKt
import com.bluedragonmc.puffin.services.DatabaseConnection
import com.bluedragonmc.puffin.services.IMapService
import com.bluedragonmc.puffin.util.Utils.handleRPC
import com.google.inject.Inject
import com.google.inject.Singleton
import com.google.protobuf.Empty
import java.util.UUID

/**
 * gRPC adapter for [MapService].
 */
@Singleton
class MapGrpcService @Inject constructor(
    private val mapService: IMapService,
    private val db: DatabaseConnection,
) : MapServiceGrpcKt.MapServiceCoroutineImplBase() {

    override suspend fun getAvailableMaps(request: Map.GetAvailableMapsRequest): Map.MapList = handleRPC {
        return Map.MapList.newBuilder().addAllMaps(
            mapService.getAvailableMaps(
                gameName = if (request.hasGameName()) request.gameName else null,
                mode = if (request.hasGameMode()) request.gameMode else null,
                mapId = null,
                if (request.hasWhitelist()) request.whitelist.playersList.map { UUID.fromString(it) } else null,
            )
        ).build()
    }

    override suspend fun updateMapConfig(request: Map.UpdateMapConfigRequest): Empty {
        db.putMapConfig(request.mapId, request.configJson)
        return Empty.getDefaultInstance()
    }
}
