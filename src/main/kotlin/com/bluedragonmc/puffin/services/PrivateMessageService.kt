package com.bluedragonmc.puffin.services

import com.github.benmanes.caffeine.cache.Caffeine
import com.google.inject.Inject
import com.google.inject.Singleton
import java.time.Duration
import java.util.*

/**
 * Sends private messages (i.e. /msg) to players on other servers
 */
@Singleton
class PrivateMessageService @Inject constructor(val playerTracker: IPlayerTracker) : Service() {

    /**
     * A cache of players to the last player they
     * sent a private message to. Used for the
     * reply functionality (/reply, /r).
     */
    private val lastReplyCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(10))
        .expireAfterAccess(Duration.ofMinutes(5))
        .build<UUID, UUID>()

    /**
     * Sends [message] from [senderUuid] to [recipientUuid], or to the sender's last reply recipient
     * if [recipientUuid] is blank.
     */
    suspend fun sendPrivateMessage(senderUuid: String, senderUsername: String, recipientUuid: String?, message: String) {
        val finalMessage =
            "<p2><lang:command.msg.received:'<p1>$senderUsername':'<gray>$message'>"
        val recipient = if (recipientUuid.isNullOrBlank()) {
            lastReplyCache.getIfPresent(UUID.fromString(senderUuid))
        } else {
            UUID.fromString(recipientUuid)
        }
        if (recipient == null) {
            playerTracker.sendChat(
                UUID.fromString(senderUuid),
                "<red>You have not replied to anyone recently!"
            )
            return // No possible recipient was found.
        }
        // Send the message to the recipient
        playerTracker.sendChat(recipient, finalMessage)
        // Update the sender's most recent recipient
        lastReplyCache.put(UUID.fromString(senderUuid), recipient)
    }
}
