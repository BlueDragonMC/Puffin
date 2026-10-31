package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.CommonTypes
import com.bluedragonmc.api.grpc.GsClientServiceGrpcKt
import com.bluedragonmc.api.grpc.PlayerHolderOuterClass
import com.bluedragonmc.api.grpc.consumeTransferRequest
import com.bluedragonmc.api.grpc.prepareTransferRequest
import com.bluedragonmc.puffin.app.PuffinConfig
import io.grpc.ManagedChannel
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerTransferServiceTest {

    private val player: UUID = UUID.randomUUID()

    private fun server(
        name: String,
        ready: Boolean = true,
        draining: Boolean = false,
        terminating: Boolean = false,
    ) = QueueServer(
        name = name,
        games = emptyList(),
        address = "10.0.0.1",
        port = 25565,
        ready = ready,
        draining = draining,
        terminating = terminating,
    )

    @Test
    fun `rejects a transfer when no replacement proxy is ready`() = runBlocking {
        val queue = FakeQueueService(mutableMapOf("server-a" to server("server-a")))
        val k8s = FakeK8sServiceDiscovery(readyProxies = listOf("proxy-old"))
        val service = PlayerTransferService(queue, k8s, PuffinConfig())

        val response = service.prepareTransfer(prepareTransferRequest {
            playerUuid = player.toString()
            proxyPodName = "proxy-old"
            serverName = "server-a"
            gameId = "game-1"
        })

        assertFalse(response.accepted)
        assertTrue(response.rejectionReason.isNotBlank())
    }

    @Test
    fun `rejects a transfer while the requesting proxy is still ready`() = runBlocking {
        val queue = FakeQueueService(mutableMapOf("server-a" to server("server-a")))
        val k8s = FakeK8sServiceDiscovery(readyProxies = listOf("proxy-old", "proxy-new"))
        val service = PlayerTransferService(queue, k8s, PuffinConfig())

        val response = service.prepareTransfer(prepareTransferRequest {
            playerUuid = player.toString()
            proxyPodName = "proxy-old"
            serverName = "server-a"
            gameId = "game-1"
        })

        assertFalse(response.accepted)
    }

    @Test
    fun `prepares a transfer and consumes it exactly once`() = runBlocking {
        val queue = FakeQueueService(mutableMapOf("server-a" to server("server-a")))
        val k8s = FakeK8sServiceDiscovery(readyProxies = listOf("proxy-new"))
        val service = PlayerTransferService(queue, k8s, PuffinConfig())

        val prepared = service.prepareTransfer(prepareTransferRequest {
            playerUuid = player.toString()
            proxyPodName = "proxy-old"
            serverName = "server-a"
            gameId = "game-1"
        })
        assertTrue(prepared.accepted)
        assertEquals("game-1", queue.destinations[player])

        val consumed = service.consumeTransfer(consumeTransferRequest { playerUuid = player.toString() })
        assertTrue(consumed.found)
        assertEquals("server-a", consumed.serverName)
        assertEquals("10.0.0.1", consumed.gameServerIp)
        assertEquals(25565, consumed.gameServerPort)
        assertEquals("game-1", consumed.gameId)
        assertEquals("game-1", queue.destinations[player])

        val second = service.consumeTransfer(consumeTransferRequest { playerUuid = player.toString() })
        assertFalse(second.found)
    }

    @Test
    fun `rejects a transfer to a draining or missing server`() = runBlocking {
        val queue = FakeQueueService(
            mutableMapOf(
                "draining" to server("draining", draining = true),
                "terminating" to server("terminating", terminating = true),
            )
        )
        val k8s = FakeK8sServiceDiscovery(readyProxies = listOf("proxy-new"))
        val service = PlayerTransferService(queue, k8s, PuffinConfig())

        for (name in listOf("draining", "terminating", "missing")) {
            val response = service.prepareTransfer(prepareTransferRequest {
                playerUuid = player.toString()
                proxyPodName = "proxy-old"
                serverName = name
                gameId = "game-1"
            })
            assertFalse(response.accepted, "expected $name to be rejected")
        }
    }

    private class FakeQueueService(
        private val servers: MutableMap<String, QueueServer> = mutableMapOf(),
    ) : IQueueService {

        val destinations = mutableMapOf<UUID, String>()

        override suspend fun getServers(): List<QueueServer> = servers.values.toList()

        override suspend fun getServer(serverName: String): QueueServer? = servers[serverName]

        override suspend fun getGame(gameId: String): Game = unsupported()

        override suspend fun getServerOfGame(gameId: String): String? = unsupported()

        override fun registerInstanceUpdateCallback(cb: (gameId: String) -> Unit) = unsupported()

        override suspend fun setGameState(gameId: String, newState: CommonTypes.GameState) = unsupported()

        override fun addToQueue(party: QueuedParty) = unsupported()

        override suspend fun removeFromQueue(player: UUID): Boolean = unsupported()

        override suspend fun processQueue() = unsupported()

        override suspend fun removeServer(name: String) = unsupported()

        override suspend fun addServer(
            name: String,
            address: String?,
            port: Int?,
            ready: Boolean,
            terminating: Boolean,
        ) = unsupported()

        override suspend fun setServerDraining(name: String, draining: Boolean) = unsupported()

        override suspend fun removeGame(serverName: String, gameId: String) = unsupported()

        override suspend fun getGamesMatching(gameType: CommonTypes.GameType): List<Game> = unsupported()

        override suspend fun addGame(
            serverName: String,
            gameId: String,
            gameType: CommonTypes.GameType,
            gameState: CommonTypes.GameState,
        ) = unsupported()

        override suspend fun getGames(): List<Game> = unsupported()

        override fun setDestination(player: UUID, gameId: String) {
            destinations[player] = gameId
        }

        override suspend fun sendPlayerToInstance(player: UUID, gameId: String) = unsupported()

        override fun consumeDestination(player: UUID): String? = destinations.remove(player)

        private fun unsupported(): Nothing = error("unused in this test")
    }

    private class FakeK8sServiceDiscovery(
        private val readyProxies: List<String> = emptyList(),
    ) : IK8sServiceDiscovery {

        override suspend fun getProxyIP(podName: String): String? = unsupported()

        override suspend fun getGameServerIP(serverName: String): String? = unsupported()

        override fun getAllProxies(): List<String> = readyProxies

        override fun getReadyProxies(): List<String> = readyProxies

        override fun isProxyReady(podName: String): Boolean = podName in readyProxies

        override suspend fun getStubToServer(
            serverName: String,
        ): GsClientServiceGrpcKt.GsClientServiceCoroutineStub? = unsupported()

        override suspend fun getChannelToServer(serverName: String): ManagedChannel? = unsupported()

        override suspend fun getChannelToProxy(proxyPodName: String): ManagedChannel? = unsupported()

        override suspend fun periodicSync() = unsupported()

        override fun registerProxyPlayerListener(
            listener: suspend (podName: String, response: PlayerHolderOuterClass.GetPlayersResponse) -> Unit,
        ) = unsupported()

        private fun unsupported(): Nothing = error("unused in this test")
    }
}
