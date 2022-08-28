package com.bluedragonmc.puffin.services

import com.bluedragonmc.puffin.app.Puffin
import com.bluedragonmc.puffin.config.ConfigService
import com.bluedragonmc.puffin.util.Utils.catchingTimer
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.model.Filters
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.litote.kmongo.coroutine.CoroutineClient
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.coroutine.CoroutineDatabase
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.reactivestreams.KMongo
import java.net.Socket
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeUnit

class DatabaseConnection(app: Puffin) : Service(app) {

    private lateinit var client: CoroutineClient

    private lateinit var db: CoroutineDatabase
    private lateinit var playersCollection: CoroutineCollection<Document>
    private lateinit var mapsCollection: CoroutineCollection<Document>

    private val builder = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(Duration.ofMinutes(10))

    private val uuidCache: Cache<String, UUID?> = builder.build()
    private val usernameCache: Cache<UUID, String?> = builder.build()
    private val mapDataCache: Cache<String, Document?> = builder.build()

    fun getPlayerName(uuid: UUID): String? = usernameCache.get(uuid) {
        runBlocking {
            playersCollection.findOneById(uuid.toString())?.getString("username")
        }
    }

    fun getPlayerUUID(username: String): UUID? = uuidCache.get(username.lowercase()) {
        runBlocking {
            playersCollection.findOne(Filters.eq("usernameLower", username))?.getString("_id")
                ?.let { UUID.fromString(it) }
        }
    }

    fun getMapInfo(mapName: String) = mapDataCache.get(mapName) {
        runBlocking {
            mapsCollection.findOneById(mapName)
        }
    }

    private fun onConnected() {
        val config = app.get(ConfigService::class).config
        client = KMongo.createClient(MongoClientSettings.builder()
            .applyConnectionString(ConnectionString("mongodb://${config.mongoHostname}:${config.mongoPort}"))
            .applyToSocketSettings { block ->
                block.connectTimeout(5, TimeUnit.SECONDS)
            }.applyToClusterSettings { block ->
                block.serverSelectionTimeout(5, TimeUnit.SECONDS)
            }.build()).coroutine

        db = client.getDatabase("bluedragon")

        playersCollection = db.getCollection("players")
        mapsCollection = db.getCollection("maps")
    }

    override fun initialize() {
        val config = app.get(ConfigService::class).config
        // Wait for the port to become available, then connect to the database normally.
        catchingTimer("mongo-connection-test", daemon = false, period = 5_000) {
            try {
                // Check if MongoDB is ready for requests
                Socket(config.mongoHostname,
                    config.mongoPort).close() // Check if the port is open first; this is faster and doesn't require the creation of a whole client
                KMongo.createClient(MongoClientSettings.builder()
                    .applyConnectionString(ConnectionString("mongodb://${config.mongoHostname}:${config.mongoPort}"))
                    .applyToSocketSettings { settings ->
                        settings.connectTimeout(2, TimeUnit.SECONDS)
                        settings.readTimeout(2, TimeUnit.SECONDS)
                    }.build())
                    .close() // Create a client to verify that MongoDB is fully started and running on this port
            } catch (ignored: Throwable) {
                logger.debug("Waiting 5 seconds to retry connection to MongoDB.")
                return@catchingTimer
            }

            logger.info("Connected to MongoDB.")
            onConnected()
            this.cancel()
        }
    }

    override fun close() {
        client.close()

        usernameCache.invalidateAll()
        usernameCache.cleanUp()

        uuidCache.invalidateAll()
        uuidCache.cleanUp()
    }
}
