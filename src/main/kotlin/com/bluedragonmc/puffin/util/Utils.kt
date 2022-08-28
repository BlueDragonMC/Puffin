package com.bluedragonmc.puffin.util

import com.bluedragonmc.messages.ChatType
import com.bluedragonmc.messages.SendChatMessage
import com.bluedragonmc.puffin.services.MessagingService
import com.bluedragonmc.puffin.services.Service
import com.bluedragonmc.puffin.services.ServiceHolder
import java.util.*
import kotlin.concurrent.fixedRateTimer

object Utils {
    lateinit var app: ServiceHolder

    private fun getAMQPClient() = app.get(MessagingService::class).client

    fun sendChat(player: UUID, message: String, chatType: ChatType = ChatType.CHAT) =
        getAMQPClient().publish(SendChatMessage(player, message, chatType))

    fun sendChat(players: Collection<UUID>, message: String, chatType: ChatType = ChatType.CHAT) {
        for (player in players) sendChat(player, message, chatType)
    }

    inline fun catchingTimer(
        name: String? = null,
        daemon: Boolean = false,
        initialDelay: Long = 0.toLong(),
        period: Long,
        crossinline action: TimerTask.() -> Unit,
    ) =
        fixedRateTimer(name, daemon, initialDelay, period) {
            try {
                action()
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }

    class UtilsService(app: ServiceHolder) : Service(app) {
        override fun initialize() {
            Utils.app = app
        }

        override fun close() {}

    }
}