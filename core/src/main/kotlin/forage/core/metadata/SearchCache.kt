package forage.core.metadata

import forage.core.PlatformId
import forage.core.db.ForageDatabase
import forage.core.source.VideoStub
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant

/** Keeps search results for a while, so asking for a new batch doesn't repeat the same searches. */
class SearchCache(
    db: ForageDatabase,
    private val maxAge: Duration = Duration.ofDays(1),
    private val clock: () -> Instant = Instant::now,
) {
    private val queries = db.metadataQueries

    fun get(platform: String, query: String): List<VideoStub>? {
        val row = queries.selectSearch(platform, query).executeAsOneOrNull() ?: return null
        if (Instant.ofEpochSecond(row.fetched_at) < clock() - maxAge) return null
        return Json.decodeFromString<List<Stored>>(row.results).map { it.toStub() }
    }

    fun put(platform: String, query: String, results: List<VideoStub>) {
        queries.upsertSearch(platform, query, Json.encodeToString(results.map(::Stored)), clock().epochSecond)
    }

    @Serializable
    private data class Stored(
        val id: String,
        val title: String,
        val channel: String? = null,
        val channelName: String? = null,
        val durationSeconds: Long? = null,
        val isShort: Boolean = false,
        val uploadedAt: Long? = null,
        val tags: List<String> = emptyList(),
    ) {
        constructor(s: VideoStub) : this(
            s.id.toString(), s.title, s.channel?.toString(), s.channelName, s.durationSeconds, s.isShort, s.uploadedAt?.epochSecond, s.tags,
        )

        fun toStub() = VideoStub(
            PlatformId.parse(id), title, channel?.let(PlatformId::parse), channelName, durationSeconds, isShort,
            uploadedAt?.let(Instant::ofEpochSecond), tags,
        )
    }
}
