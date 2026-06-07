package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.CommonTypes
import com.bluedragonmc.puffin.app.Env
import com.bluedragonmc.puffin.app.Env.LUCKPERMS_API_URL
import com.bluedragonmc.puffin.app.Env.MONGO_CONNECTION_STRING
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.inject.Singleton
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.model.Filters
import com.mongodb.client.result.UpdateResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.bson.Document
import org.bson.conversions.Bson
import org.litote.kmongo.coroutine.CoroutineClient
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.coroutine.CoroutineDatabase
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.reactivestreams.KMongo
import org.litote.kmongo.upsert
import java.io.File
import java.net.Inet4Address
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Connects to MongoDB to fetch player names, UUIDs, colors, etc. Caches responses in memory.
 */
@Singleton
class DatabaseConnection : Service() {

    private val mongoClient: CoroutineClient
    private val httpClient: OkHttpClient

    private val cacheControl = CacheControl.Builder().maxAge(30, TimeUnit.SECONDS).build()
    private val gson = Gson()

    private val db: CoroutineDatabase
    private val playersCollection: CoroutineCollection<Document>
    private val mapDataCollection: CoroutineCollection<MapData>
    private val mapConfigCollection: CoroutineCollection<Document>

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

    suspend fun getMapData(id: String) =
        mapDataCollection.findOneById(id)?.data

    suspend fun putMapData(id: String, data: ByteArray) =
        mapDataCollection.updateOneById(id = id, update = MapData(data), options = upsert())

    suspend fun getMapConfig(id: String) = mapConfigCollection.findOne(Filters.eq("world.id", id))

    suspend fun putMapConfig(id: String, data: String): UpdateResult =
        mapConfigCollection.updateOne(
            filter = Filters.eq("world.id", id),
            update = Document("\$set", Document.parse(data)),
            options = upsert()
        )

    suspend fun getAvailableMaps(gameName: String?, mode: String?, whitelistedPlayers: Iterable<UUID>?): List<CommonTypes.MapSource> {
        val filters = mutableListOf<Bson>()
        if (gameName != null || mode != null) {
            val doc = Document()
            if (gameName != null) doc["name"] = gameName
            if (mode != null) doc["mode"] = mode
            filters += Filters.elemMatch("world.games", doc)
        }
        if (whitelistedPlayers != null) {
            for (player in whitelistedPlayers) {
                filters += Filters.eq("whitelist", player.toString())
            }
        }
        // TODO do not allow player to join a whitelisted map if they aren't on the whitelist

        val docs = mapConfigCollection.find(*filters.toTypedArray()).toList()

        return docs.map { doc ->
            val mapId = doc.getString("_id")
            CommonTypes.MapSource.newBuilder()
                .setMapId(mapId)
                .setMapConfig(doc.toJson())
                .setMapFormat(CommonTypes.MapFormat.POLAR)
                .setMapUrl("http://${Inet4Address.getLocalHost().hostAddress}:${Env.MAP_SERVICE_PORT}/map/$mapId/data")
                .build()
        }
    }

    fun evictCachesForPlayer(player: UUID) {
        val username = usernameCache.getIfPresent(player)
        if (username != null) {
            uuidCache.invalidate(username)
        }
        usernameCache.invalidate(player)
        userColorCache.invalidate(player)
    }

    init {
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
        mapDataCollection = db.getCollection("mapData")
        mapConfigCollection = db.getCollection("mapConfig")
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

    @Serializable
    data class MapData(val data: ByteArray)
}
