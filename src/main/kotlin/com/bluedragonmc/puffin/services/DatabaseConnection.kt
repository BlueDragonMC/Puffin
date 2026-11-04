package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.CommonTypes
import com.bluedragonmc.puffin.app.PuffinConfig
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.inject.Inject
import com.google.inject.Singleton
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.model.Filters
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import com.mongodb.client.result.UpdateResult
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.bson.BsonDocument
import org.bson.Document
import org.bson.codecs.kotlinx.ByteArrayAsBsonBinary
import org.bson.conversions.Bson
import java.io.File
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Connects to MongoDB to fetch player names, UUIDs, colors, etc. Caches responses in memory.
 */
@Singleton
class DatabaseConnection @Inject constructor(private val config: PuffinConfig) : Service() {

    private val mongoClient: MongoClient
    private val httpClient: OkHttpClient

    private val cacheControl = CacheControl.Builder().maxAge(30, TimeUnit.SECONDS).build()
    private val gson = Gson()

    private val db: MongoDatabase
    private val playersCollection: MongoCollection<Document>
    private val mapDataCollection: MongoCollection<MapData>
    private val mapConfigCollection: MongoCollection<Document>

    private val builder = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(Duration.ofMinutes(10))

    private val uuidCache: Cache<String, UUID?> = builder.build()
    private val usernameCache: Cache<UUID, String?> = builder.build()
    private val userColorCache: Cache<UUID, String> = builder.build()

    suspend fun getPlayerNameColor(uuid: UUID): String {
        userColorCache.getIfPresent(uuid)?.let { return it }
        val color = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${config.luckpermsApiUrl}/user/$uuid/meta")
                .get()
                .build()
            val responseBody = httpClient.newCall(request).execute().body.string()
            val reply = gson.fromJson(responseBody, JsonObject::class.java)
            reply.get("meta")?.asJsonObject?.get("rankcolor")?.asString ?: "#aaaaaa"
        }
        userColorCache.put(uuid, color)
        return color
    }

    suspend fun getPlayerName(uuid: UUID): String? {
        usernameCache.getIfPresent(uuid)?.let { return it }
        val username = playersCollection.find(Filters.eq("_id", uuid.toString())).firstOrNull()?.getString("username")
        if (username != null) usernameCache.put(uuid, username)
        return username
    }

    suspend fun getPlayerUUID(username: String): UUID? {
        val key = username.lowercase()
        uuidCache.getIfPresent(key)?.let { return it }
        val uuid = playersCollection.find(Filters.eq("usernameLower", username)).firstOrNull()?.getString("_id")
            ?.let { UUID.fromString(it) }
        if (uuid != null) uuidCache.put(key, uuid)
        return uuid
    }

    suspend fun getMapData(id: String) =
        mapDataCollection.find(Filters.eq("_id", id)).firstOrNull()?.data

    suspend fun putMapData(id: String, data: ByteArray) =
        mapDataCollection.updateOne(
            filter = Filters.eq("_id", id),
            update = Updates.set("data", data),
            options = UpdateOptions().upsert(true)
        )

    suspend fun putMapConfig(id: String, data: String): UpdateResult =
        mapConfigCollection.updateOne(
            filter = Filters.eq("_id", id),
            update = Document("\$set", Document.parse(data)),
            options = UpdateOptions().upsert(true)
        )

    suspend fun getAvailableMaps(gameName: String?, mode: String?, mapId: String?, whitelistedPlayers: Iterable<UUID>?): List<CommonTypes.MapSource> {
        val filters = mutableListOf<Bson>()
        if (mapId != null) {
            filters += Filters.eq("_id", mapId)
        }
        if (gameName != null || mode != null) {
            val doc = Document()
            if (gameName != null) doc["name"] = gameName
            if (mode != null) doc["mode"] = mode
            filters += Filters.elemMatch("world.games", doc)
        }
        if (whitelistedPlayers != null) {
            val clause = mutableListOf<Bson>()
            for (player in whitelistedPlayers) {
                clause += Filters.eq("world.whitelist", player.toString())
            }
            filters += Filters.or(
                Filters.eq("world.whitelist", null),
                Filters.and(clause)
            )
        }

        val filter = when (filters.size) {
            0 -> BsonDocument()
            1 -> filters.first()
            else -> Filters.and(filters)
        }
        val docs = mapConfigCollection.find(filter).toList()

        return docs.map { doc -> createMapSourceFromDocument(doc) }
    }

    private fun createMapSourceFromDocument(doc: Document): CommonTypes.MapSource {
        val mapId = doc.getString("_id")
        return CommonTypes.MapSource.newBuilder()
            .setMapId(mapId)
            .setMapConfig(doc.toJson())
            .setMapFormat(CommonTypes.MapFormat.POLAR)
            .setMapUrl("http://${config.mapServiceHost}:${config.mapServicePort}/map/$mapId/data")
            .build()
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

        mongoClient = MongoClient.create(MongoClientSettings.builder()
            .applyConnectionString(ConnectionString(config.mongoConnectionString))
            .applyToSocketSettings { block ->
                block.connectTimeout(5, TimeUnit.SECONDS)
            }.applyToClusterSettings { block ->
                block.serverSelectionTimeout(5, TimeUnit.SECONDS)
            }.build()
        )

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
    data class MapData(
        @Serializable(with = ByteArrayAsBsonBinary::class) val data: ByteArray
    )
}
