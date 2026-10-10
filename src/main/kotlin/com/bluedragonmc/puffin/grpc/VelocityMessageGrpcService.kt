package com.bluedragonmc.puffin.grpc

import com.bluedragonmc.api.grpc.VelocityMessage
import com.bluedragonmc.api.grpc.VelocityMessageServiceGrpcKt
import com.bluedragonmc.puffin.services.PrivateMessageService
import com.bluedragonmc.puffin.util.Utils.handleRPC
import com.google.inject.Inject
import com.google.inject.Singleton
import com.google.protobuf.Empty

/**
 * gRPC adapter for private messages (i.e. /msg).
 */
@Singleton
class VelocityMessageGrpcService @Inject constructor(
    private val privateMessageService: PrivateMessageService,
) : VelocityMessageServiceGrpcKt.VelocityMessageServiceCoroutineImplBase() {

    override suspend fun sendMessage(request: VelocityMessage.PrivateMessageRequest): Empty = handleRPC {
        privateMessageService.sendPrivateMessage(
            senderUuid = request.senderUuid,
            senderUsername = request.senderUsername,
            recipientUuid = request.recipientUuid,
            message = request.message,
        )
        return Empty.getDefaultInstance()
    }
}
