package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.VelocityMessage
import com.bluedragonmc.api.grpc.VelocityMessageServiceGrpcKt
import com.bluedragonmc.puffin.util.Utils
import com.bluedragonmc.puffin.util.Utils.handleRPC
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.protobuf.Empty
import java.time.Duration
import java.util.UUID

class PrivateMessageService(app: ServiceHolder) : Service(app) {

    /**
     * A cache of players to the last player they
     * sent a private message to. Used for the
     * reply functionality (/reply, /r).
     */
    private val lastReplyCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(10))
        .expireAfterAccess(Duration.ofMinutes(5))
        .build<UUID, UUID>()

    inner class VelocityMessageService : VelocityMessageServiceGrpcKt.VelocityMessageServiceCoroutineImplBase() {
        override suspend fun sendMessage(request: VelocityMessage.PrivateMessageRequest): Empty = handleRPC {
            val finalMessage =
                "<p2><lang:command.msg.received:'<p1>${request.senderUsername}':'<gray>${request.message}'>"
            val recipient = if (request.recipientUuid.isNullOrBlank()) {
                lastReplyCache.getIfPresent(UUID.fromString(request.senderUuid))
            } else {
                UUID.fromString(request.recipientUuid)
            }
            if (recipient == null) {
                Utils.sendChat(UUID.fromString(request.senderUuid), "<red>You have not replied to anyone recently!")
                return Empty.getDefaultInstance() // No possible recipient was found.
            }
            // Send the message to the recipient
            Utils.sendChat(recipient, finalMessage)
            // Update the sender's most recent recipient
            lastReplyCache.put(UUID.fromString(request.senderUuid), recipient)

            return Empty.getDefaultInstance()
        }
    }
}