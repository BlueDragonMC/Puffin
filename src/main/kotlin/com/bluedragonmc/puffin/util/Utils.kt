package com.bluedragonmc.puffin.util

import com.bluedragonmc.messages.ChatType
import com.bluedragonmc.messages.SendChatMessage
import com.bluedragonmc.puffin.services.MessagingService
import com.bluedragonmc.puffin.services.Service
import com.bluedragonmc.puffin.services.ServiceHolder
import java.util.*

object Utils {
    lateinit var app: ServiceHolder

    private fun getAMQPClient() = app.get(MessagingService::class).client

    fun sendChat(player: UUID, message: String, chatType: ChatType = ChatType.CHAT) =
        getAMQPClient().publish(SendChatMessage(player, message, chatType))

    class UtilsService(app: ServiceHolder) : Service(app) {
        override fun initialize() {
            Utils.app = app
        }

        override fun close() {}

    }
}