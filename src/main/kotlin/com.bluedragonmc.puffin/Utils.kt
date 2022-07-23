package com.bluedragonmc.puffin

import com.bluedragonmc.messages.ChatType
import com.bluedragonmc.messages.SendChatMessage
import com.bluedragonmc.messagingsystem.AMQPClient
import java.util.UUID

object Utils {
    lateinit var instance: AMQPClient

    fun sendChat(player: UUID, message: String) = instance.publish(SendChatMessage(player, message, ChatType.CHAT))
}