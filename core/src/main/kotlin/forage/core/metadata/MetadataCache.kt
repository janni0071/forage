package forage.core.metadata

import forage.core.PlatformId
import forage.core.db.ForageDatabase
import forage.core.db.Related_video
import forage.core.db.Video
import forage.core.source.VideoDetails
import forage.core.source.VideoMetadata
import forage.core.source.VideoStub
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant

/** Everything fetched from a platform is kept here, so each video is requested once (SPEC, Risks). */
class MetadataCache(db: ForageDatabase, private val clock: () -> Instant = Instant::now) {
    private val queries = db.metadataQueries

    /** Cached details for [id], or null if never fetched or fetched longer than [maxAge] ago. */
    fun video(id: PlatformId, maxAge: Duration? = null): VideoDetails? {
        val row = queries.selectVideo(id.toString()).executeAsOneOrNull() ?: return null
        if (maxAge != null && Instant.ofEpochSecond(row.fetched_at) < clock() - maxAge) return null
        val related = queries.selectRelated(row.id).executeAsList().map { r ->
            VideoStub(
                id = PlatformId.parse(r.related_id),
                title = r.title,
                channel = r.channel_id?.let(PlatformId::parse),
                channelName = r.channel_name,
                durationSeconds = r.duration_seconds,
                isShort = r.is_short,
                uploadedAt = r.uploaded_at?.let(Instant::ofEpochSecond),
            )
        }
        return VideoDetails(row.toMetadata(), related)
    }

    fun put(details: VideoDetails) {
        val v = details.metadata
        val key = v.id.toString()
        queries.transaction {
            queries.upsertVideo(
                Video(
                    id = key,
                    title = v.title,
                    description = v.description,
                    tags = Json.encodeToString(v.tags),
                    duration_seconds = v.durationSeconds,
                    channel_id = v.channel?.toString(),
                    channel_name = v.channelName,
                    language = v.language,
                    thumbnail_url = v.thumbnailUrl,
                    uploaded_at = v.uploadedAt?.epochSecond,
                    is_short = v.isShort,
                    extra = Json.encodeToString(v.extra),
                    fetched_at = clock().epochSecond,
                )
            )
            queries.deleteRelated(key)
            details.related.forEachIndexed { position, r ->
                queries.insertRelated(
                    Related_video(
                        video_id = key,
                        position = position.toLong(),
                        related_id = r.id.toString(),
                        title = r.title,
                        channel_id = r.channel?.toString(),
                        channel_name = r.channelName,
                        duration_seconds = r.durationSeconds,
                        is_short = r.isShort,
                        uploaded_at = r.uploadedAt?.epochSecond,
                    )
                )
            }
            queries.deleteUnavailable(key)
        }
    }

    /** Why [id] is known to be gone, or null if it isn't. */
    fun unavailableReason(id: PlatformId): String? =
        queries.selectUnavailable(id.toString()).executeAsOneOrNull()

    fun putUnavailable(id: PlatformId, reason: String) {
        queries.upsertUnavailable(id.toString(), reason, clock().epochSecond)
    }

    /** Metadata of every cached video, without related videos: one query, for list views. */
    fun allMetadata(): Map<PlatformId, VideoMetadata> =
        queries.selectAllVideos().executeAsList().associate { PlatformId.parse(it.id) to it.toMetadata() }

    /** Every cached video id. */
    fun ids(): List<PlatformId> = queries.selectVideoIds().executeAsList().map(PlatformId::parse)

    fun size(): Long = queries.countVideos().executeAsOne()

    private fun Video.toMetadata() = VideoMetadata(
        id = PlatformId.parse(id),
        title = title,
        description = description,
        tags = Json.decodeFromString(tags),
        durationSeconds = duration_seconds,
        channel = channel_id?.let(PlatformId::parse),
        channelName = channel_name,
        language = language,
        thumbnailUrl = thumbnail_url,
        uploadedAt = uploaded_at?.let(Instant::ofEpochSecond),
        isShort = is_short,
        extra = Json.decodeFromString(extra),
    )
}
