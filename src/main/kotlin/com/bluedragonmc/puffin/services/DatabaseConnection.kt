package com.bluedragonmc.puffin.services

import com.bluedragonmc.puffin.app.Env.LUCKPERMS_API_URL
import com.bluedragonmc.puffin.app.Env.MONGO_CONNECTION_STRING
import com.bluedragonmc.puffin.app.Puffin
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

    private val uuidCache: Cache<String, UUID?> = builder.build()
    private val usernameCache: Cache<UUID, String?> = builder.build()
    private val userColorCache: Cache<UUID, String> = builder.build()

    fun getPlayerNameColor(uuid: UUID): String = userColorCache.get(uuid) {
        val request = Request.Builder()
            .url("$LUCKPERMS_API_URL/user/$uuid/meta")
            .get()
            .build()
        val responseBody = httpClient.newCall(request).execute().body?.string()
        val reply = gson.fromJson(responseBody, JsonObject::class.java)
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

    override fun initialize() {
        this.httpClient = OkHttpClient.Builder()
            .cache(okhttp3.Cache(File("/tmp/okhttp"), 50_000_000))
            .addNetworkInterceptor { chain ->
                chain.proceed(chain.request()).newBuilder()
                    .addHeader("Cache-Control", cacheControl.toString())
                    .build()
            }
            .build()

        mongoClient = KMongo.createClient(MongoClientSettings.builder()
            .applyConnectionString(ConnectionString(MONGO_CONNECTION_STRING))
            .applyToSocketSettings { block ->
                block.connectTimeout(5, TimeUnit.SECONDS)
            }.applyToClusterSettings { block ->
                block.serverSelectionTimeout(5, TimeUnit.SECONDS)
            }.build()
        ).coroutine

        db = mongoClient.getDatabase("bluedragon")

        playersCollection = db.getCollection("players")
        mapsCollection = db.getCollection("maps")

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
