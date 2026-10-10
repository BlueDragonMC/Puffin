package com.bluedragonmc.puffin.util

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.inject.Singleton
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Creates and caches gRPC [ManagedChannel]s.
 */
@Singleton
class GrpcChannels {

    private val logger = LoggerFactory.getLogger(GrpcChannels::class.java)

    private val channels: Cache<ChannelKey, ManagedChannel> = Caffeine.newBuilder()
        .expireAfterAccess(Duration.ofMinutes(5))
        .expireAfterWrite(Duration.ofMinutes(10))
        .evictionListener { key: ChannelKey?, channel: ManagedChannel?, _ ->
            // Shut down channels when they are removed from the cache.
            if (channel != null && !channel.isShutdown) {
                channel.shutdown()
            }
        }
        .build()

    fun channelTo(addr: String, port: Int): ManagedChannel {
        return channels.get(ChannelKey(addr, port)) {
            logger.debug("Building managed channel with address '$addr' and port '$port'.")
            ManagedChannelBuilder.forAddress(addr, port).usePlaintext().build()
        }
    }

    /** Shuts down every channel to [addr], regardless of port. */
    fun close(addr: String) {
        channels.asMap().keys.filter { it.addr == addr }.forEach { key ->
            channels.getIfPresent(key)?.shutdown()
            channels.invalidate(key)
        }
    }

    /** Shuts down and clears every cached channel. Called during shutdown. */
    fun closeAll() {
        channels.asMap().values.forEach { channel ->
            if (!channel.isShutdown) {
                channel.shutdown()
            }
        }
        channels.invalidateAll()
        channels.cleanUp()
    }

    private data class ChannelKey(val addr: String, val port: Int)
}
