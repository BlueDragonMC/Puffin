package com.bluedragonmc.puffin.services

import com.bluedragonmc.puffin.app.Env.LUCKPERMS_API_URL
import com.bluedragonmc.puffin.app.Env.MONGO_HOSTNAME
import com.bluedragonmc.puffin.app.Env.MONGO_PORT
import com.bluedragonmc.puffin.app.Puffin
import com.bluedragonmc.puffin.util.Utils.catchingTimer
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.model.Filters
import kotlinx.coroutines.runBlocking
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.bson.Document
import org.litote.kmongo.coroutine.CoroutineClient
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.coroutine.CoroutineDatabase
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.reactivestreams.KMongo
import java.io.File
import java.net.Socket
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Connects to MongoDB to fetch player names, UUIDs, colors, etc. Caches responses in memory.
 */
class DatabaseConnection(app: Puffin) : Service(app) {

    private lateinit var mongoClient: CoroutineClient
    private lateinit var httpClient: OkHttpClient

    private val cacheControl = CacheControl.Builder().maxAge(30, TimeUnit.SECONDS).build()
    private val gson = Gson()

    private lateinit var db: CoroutineDatabase
    private lateinit var playersCollection: CoroutineCollection<Document>
    private lateinit var mapsCollection: CoroutineCollection<Document>

    private val builder = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(Duration.ofMinutes(10))

    private val uuidCache: Cache<String, UUID> = builder.build()
    private val usernameCache: Cache<UUID, String> = builder.build()
    private val userColorCache: Cache<UUID, String> = builder.build()

    fun getPlayerNameColor(uuid: UUID): String = userColorCache.get(uuid) {
        val request = Request.Builder()
            .url("$LUCKPERMS_API_URL/user/$uuid/meta")
            .get()
            .build()
        val reply = gson.fromJson(httpClient.newCall(request).execute().body?.toString(), JsonObject::class.java)
        reply.get("meta")?.asJsonObject?.get("rankcolor")?.asString ?: "#aaaaaa"
    }

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

    private fun evictCachesForPlayer(player: UUID) {
        val username = usernameCache.getIfPresent(player)
        if (username != null) {
            uuidCache.invalidate(username)
        }
        usernameCache.invalidate(player)
        userColorCache.invalidate(player)
    }

    private fun onConnected() {
        mongoClient = KMongo.createClient(MongoClientSettings.builder()
            .applyConnectionString(ConnectionString("mongodb://$MONGO_HOSTNAME:$MONGO_PORT"))
            .applyToSocketSettings { block ->
                block.connectTimeout(5, TimeUnit.SECONDS)
            }.applyToClusterSettings { block ->
                block.serverSelectionTimeout(5, TimeUnit.SECONDS)
            }.build()
        ).coroutine

        db = mongoClient.getDatabase("bluedragon")

        playersCollection = db.getCollection("players")
        mapsCollection = db.getCollection("maps")
    }

    override fun initialize() {
        this.httpClient = OkHttpClient.Builder()
            .cache(okhttp3.Cache(File("/tmp/okhttp"), 50_000_000))
            .addNetworkInterceptor { chain ->
                chain.proceed(chain.request()).newBuilder()
                    .addHeader("Cache-Control", cacheControl.toString())
                    .build()
            }
            .build()
        // Wait for the port to become available, then connect to the database normally.
        catchingTimer("mongo-connection-test", daemon = false, period = 5_000) {
            try {
                // Check if MongoDB is ready for requests
                Socket(
                    MONGO_HOSTNAME,
                    MONGO_PORT
                ).close() // Check if the port is open first; this is faster and doesn't require the creation of a whole client
                KMongo.createClient(MongoClientSettings.builder()
                    .applyConnectionString(ConnectionString("mongodb://$MONGO_HOSTNAME:$MONGO_PORT"))
                    .applyToSocketSettings { settings ->
                        settings.connectTimeout(2, TimeUnit.SECONDS)
                        settings.readTimeout(2, TimeUnit.SECONDS)
                    }.build()
                )
                    .close() // Create a client to verify that MongoDB is fully started and running on this port
            } catch (ignored: Throwable) {
                logger.debug("Waiting 5 seconds to retry connection to MongoDB.")
                return@catchingTimer
            }

            logger.info("Connected to MongoDB.")
            onConnected()
            this.cancel()
        }
        app.get(PlayerTracker::class).onLogout(::evictCachesForPlayer)
    }

    override fun close() {
        mongoClient.close()

        usernameCache.invalidateAll()
        usernameCache.cleanUp()

        userColorCache.invalidateAll()
        userColorCache.cleanUp()

        uuidCache.invalidateAll()
        uuidCache.cleanUp()
    }
}
