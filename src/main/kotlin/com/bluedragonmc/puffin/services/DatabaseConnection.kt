package com.bluedragonmc.puffin.services

import com.bluedragonmc.puffin.app.Puffin
import com.bluedragonmc.puffin.config.ConfigService
import com.bluedragonmc.puffin.util.Utils.catchingTimer
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.model.Filters
import org.bson.Document
import org.litote.kmongo.coroutine.CoroutineClient
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.coroutine.CoroutineDatabase
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.reactivestreams.KMongo
import java.net.Socket
import java.util.*
import java.util.concurrent.TimeUnit

class DatabaseConnection(app: Puffin) : Service(app) {

    private lateinit var client: CoroutineClient

    private lateinit var db: CoroutineDatabase
    private lateinit var playersCollection: CoroutineCollection<Document>
    private lateinit var mapsCollection: CoroutineCollection<Document>

    suspend fun getPlayerName(uuid: UUID): String? {
        val doc = playersCollection.findOneById(uuid.toString()) ?: return null
        return doc.getString("username")
    }

    suspend fun getPlayerUUID(username: String): UUID? {
        val doc = playersCollection.findOne(Filters.eq("usernameLower", username)) ?: return null
        return UUID.fromString(doc.getString("_id"))
    }

    suspend fun getMapInfo(mapName: String) = mapsCollection.findOneById(mapName)

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
                    }.build()).close() // Create a client to verify that MongoDB is fully started and running on this port
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
    }
}
