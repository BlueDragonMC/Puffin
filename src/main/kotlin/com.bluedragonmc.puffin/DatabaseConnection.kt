package com.bluedragonmc.puffin

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.model.Filters
import kotlinx.coroutines.*
import org.bson.Document
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.reactivestreams.KMongo
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

object DatabaseConnection {

    internal val IO = object : CoroutineScope {
        override val coroutineContext: CoroutineContext =
            Dispatchers.IO + SupervisorJob() + CoroutineName("Database IO")
    }

    private val client = KMongo.createClient(MongoClientSettings.builder()
        .applyConnectionString(ConnectionString("mongodb://localhost:27017")).applyToSocketSettings { block ->
            block.connectTimeout(5, TimeUnit.SECONDS)
        }.applyToClusterSettings { block ->
            block.serverSelectionTimeout(5, TimeUnit.SECONDS)
        }.build()).coroutine

    private val db = client.getDatabase("bluedragon")
    private val playersCollection = db.getCollection<Document>("players")
    private val mapsCollection = db.getCollection<Document>("maps")

    fun UUID.getUsername() = runBlocking { getPlayerName(this@getUsername) ?: this.toString() }

    suspend fun getPlayerName(uuid: UUID): String? {
        val doc = playersCollection.findOneById(uuid.toString()) ?: return null
        return doc.getString("username")
    }

    suspend fun getPlayerUUID(username: String): UUID? {
        val doc = playersCollection.findOne(Filters.eq("usernameLower", username)) ?: return null
        return UUID.fromString(doc.getString("_id"))
    }

    suspend fun getMapInfo(mapName: String) = mapsCollection.findOneById(mapName)
}
