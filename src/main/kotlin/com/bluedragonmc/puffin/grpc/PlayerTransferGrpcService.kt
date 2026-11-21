package com.bluedragonmc.puffin.grpc

import com.bluedragonmc.api.grpc.PlayerTransferGrpcKt
import com.bluedragonmc.api.grpc.Transfer
import com.bluedragonmc.puffin.services.IPlayerTransferService
import com.bluedragonmc.puffin.util.Utils.handleRPC
import com.google.inject.Inject
import com.google.inject.Singleton

/**
 * gRPC adapter for proxy handoffs.
 */
@Singleton
class PlayerTransferGrpcService @Inject constructor(
    private val transferService: IPlayerTransferService,
) : PlayerTransferGrpcKt.PlayerTransferCoroutineImplBase() {

    override suspend fun prepareTransfer(request: Transfer.PrepareTransferRequest): Transfer.PrepareTransferResponse =
        handleRPC { transferService.prepareTransfer(request) }

    override suspend fun consumeTransfer(request: Transfer.ConsumeTransferRequest): Transfer.ConsumeTransferResponse =
        handleRPC { transferService.consumeTransfer(request) }
}
