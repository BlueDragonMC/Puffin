package com.bluedragonmc.puffin.util

import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.MDC
import java.util.*

/** Structured logging helpers using SLF4J MDC */
object LogContext {

    fun fields(vararg pairs: Pair<String, Any?>): Map<String, String> = buildMap {
        for ((key, value) in pairs) {
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) put(key, text)
        }
    }

    fun player(uuid: UUID, username: String? = null): Map<String, String> = fields(
        "player" to username,
        "player_uuid" to uuid,
    )

    fun player(uuid: String, username: String? = null): Map<String, String> = fields(
        "player" to username,
        "player_uuid" to uuid,
    )

    fun game(
        gameId: String?,
        game: String? = null,
        server: String? = null,
        proxy: String? = null,
        instance: String? = null,
        map: String? = null,
    ): Map<String, String> = fields(
        "game" to game,
        "game_id" to gameId,
        "server" to server,
        "proxy" to proxy,
        "instance_uuid" to instance,
        "map" to map,
    )

    fun party(
        partyId: String? = null,
        size: Int? = null,
        player: UUID? = null,
        username: String? = null,
    ): Map<String, String> = fields(
        "party_id" to partyId,
        "party_size" to size,
        "player" to username,
        "player_uuid" to player,
    )

    fun server(
        server: String? = null,
        proxy: String? = null,
        gameId: String? = null,
    ): Map<String, String> = fields(
        "server" to server,
        "proxy" to proxy,
        "game_id" to gameId,
    )

    fun task(name: String): Map<String, String> = fields("task" to name)

    fun <T> with(fields: Map<String, String>, block: () -> T): T {
        if (fields.isEmpty()) return block()
        val previous = fields.keys.associateWith { MDC.get(it) }
        try {
            fields.forEach { (key, value) -> MDC.put(key, value) }
            return block()
        } finally {
            previous.forEach { (key, value) ->
                if (value == null) MDC.remove(key) else MDC.put(key, value)
            }
        }
    }

    fun <T> with(vararg pairs: Pair<String, Any?>, block: () -> T): T =
        with(fields(*pairs), block)

    suspend fun <T> withSuspend(fields: Map<String, String>, block: suspend () -> T): T {
        if (fields.isEmpty()) return block()
        return withContext(MDCContext(fields)) { block() }
    }

    suspend fun <T> withSuspend(vararg pairs: Pair<String, Any?>, block: suspend () -> T): T =
        withSuspend(fields(*pairs)) { block() }
}
