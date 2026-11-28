package forage.core.profile

import forage.core.PlatformId
import forage.core.db.ForageDatabase
import forage.core.db.Pick as PickRow
import java.time.Instant

/** A profile's decision about a video. Videos without one are neither liked nor rejected. */
enum class VideoState(val stored: String) {
    /** Liked: part of the profile's taste, which recommendations come from. */
    LIKED("liked"),

    /** "Not interested": never suggested, and steers recommendations away from similar videos. */
    NOT_INTERESTED("not_interested");

    companion object {
        fun of(stored: String): VideoState? = entries.firstOrNull { it.stored == stored }
    }
}

data class ProfileRef(val id: Long, val name: String)

data class ProfileEntry(
    val video: PlatformId,
    val state: VideoState,
    val weight: Float,
    val source: String,
    /** When the video was liked or turned down. */
    val changedAt: Instant? = null,
)

/** One recommendation of the latest Discover batch, as stored for the feedback buttons. */
data class StoredPick(
    val position: Int,
    val video: PlatformId,
    val title: String,
    val channel: PlatformId?,
    val channelName: String?,
    val score: Float,
    val reason: String,
    val foundVia: String,
    val exploration: Boolean,
    val durationSeconds: Long? = null,
    val uploadedAt: Instant? = null,
)

/** The taste profiles of a vault. Profiles share the vault's library but never each other's decisions. */
class ProfileStore(private val db: ForageDatabase, private val clock: () -> Instant = Instant::now) {
    private val queries = db.libraryQueries

    /** The profile called [name], created on first use. */
    fun profile(name: String = DEFAULT): ProfileRef {
        queries.selectProfileByName(name).executeAsOneOrNull()?.let { return ProfileRef(it.id, it.name) }
        queries.insertProfile(name, clock().epochSecond)
        return queries.selectProfileByName(name).executeAsOne().let { ProfileRef(it.id, it.name) }
    }

    fun profiles(): List<ProfileRef> = queries.selectProfiles().executeAsList().map { ProfileRef(it.id, it.name) }

    /** The profile called [name] if it exists; never creates one. */
    fun find(name: String): ProfileRef? = queries.selectProfileByName(name).executeAsOneOrNull()?.let { ProfileRef(it.id, it.name) }

    /**
     * Deletes a profile with its decisions, picks and topic preferences. The vault's library,
     * watch history and watch-later queue are shared and stay.
     */
    fun delete(profile: ProfileRef) {
        queries.transaction {
            queries.deleteProfileVideos(profile.id)
            queries.deletePicks(profile.id)
            db.topicQueries.deleteTopicPreferencesOfProfile(profile.id)
            queries.deleteProfile(profile.id)
        }
    }

    /** Sets the state of [videos] in [profile]; null clears the decision. */
    fun mark(profile: ProfileRef, videos: Collection<PlatformId>, state: VideoState?, weight: Float = 1f, source: String = MANUAL) {
        val now = clock().epochSecond
        queries.transaction {
            for (video in videos) {
                if (state == null) queries.deleteProfileVideo(profile.id, video.toString())
                else queries.upsertProfileVideo(profile.id, video.toString(), state.stored, weight.toDouble(), source, now)
            }
        }
    }

    fun entries(profile: ProfileRef, state: VideoState? = null): List<ProfileEntry> =
        queries.selectProfileVideos(profile.id).executeAsList()
            .mapNotNull { row -> VideoState.of(row.state)?.let { ProfileEntry(PlatformId.parse(row.video_id), it, row.weight.toFloat(), row.source, Instant.ofEpochSecond(row.changed_at)) } }
            .filter { state == null || it.state == state }

    // Feedback on a recommendation (SPEC, Feedback).

    /** Like on a recommendation: the same as a like anywhere else. */
    fun like(profile: ProfileRef, video: PlatformId) =
        mark(profile, listOf(video), VideoState.LIKED, source = DISCOVER)

    /** "Not interested": becomes a negative example. */
    fun notInterested(profile: ProfileRef, video: PlatformId) =
        mark(profile, listOf(video), VideoState.NOT_INTERESTED, source = DISCOVER)

    /** Replaces the stored Discover batch of [profile]. */
    fun savePicks(profile: ProfileRef, picks: List<StoredPick>) {
        val now = clock().epochSecond
        queries.transaction {
            queries.deletePicks(profile.id)
            for (p in picks) {
                queries.insertPick(
                    PickRow(
                        profile.id, p.position.toLong(), p.video.toString(), p.title, p.channel?.toString(), p.channelName,
                        p.score.toDouble(), p.reason, p.foundVia, p.exploration, now,
                        p.durationSeconds, p.uploadedAt?.epochSecond,
                    )
                )
            }
        }
    }

    fun picks(profile: ProfileRef): List<StoredPick> = queries.selectPicks(profile.id).executeAsList().map {
        StoredPick(
            it.position.toInt(), PlatformId.parse(it.video_id), it.title, it.channel_id?.let(PlatformId::parse), it.channel_name,
            it.score.toFloat(), it.reason, it.found_via, it.exploration,
            it.duration_seconds, it.uploaded_at?.let(Instant::ofEpochSecond),
        )
    }

    companion object {
        const val DEFAULT = "Default"
        const val MANUAL = "manual"
        const val DISCOVER = "discover"
    }
}
