package forage.core.profile

import forage.core.PlatformId
import forage.core.db.ForageDatabase
import forage.core.db.Pick as PickRow
import java.time.Instant

/** A profile's decision about a video. Videos without a decision are simply undecided. */
enum class VideoState {
    /** Counts towards the taste profile. */
    INCLUDED,

    /** Left out of the profile and never suggested; says nothing about taste. */
    EXCLUDED,

    /** "Not for me": never suggested, and steers recommendations away from similar videos. */
    NEGATIVE,
}

data class ProfileRef(val id: Long, val name: String)

data class ProfileEntry(val video: PlatformId, val state: VideoState, val weight: Float, val source: String)

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
class ProfileStore(db: ForageDatabase, private val clock: () -> Instant = Instant::now) {
    private val queries = db.libraryQueries

    /** The profile called [name], created on first use. */
    fun profile(name: String = DEFAULT): ProfileRef {
        queries.selectProfileByName(name).executeAsOneOrNull()?.let { return ProfileRef(it.id, it.name) }
        queries.insertProfile(name, clock().epochSecond)
        return queries.selectProfileByName(name).executeAsOne().let { ProfileRef(it.id, it.name) }
    }

    fun profiles(): List<ProfileRef> = queries.selectProfiles().executeAsList().map { ProfileRef(it.id, it.name) }

    /** Sets the state of [videos] in [profile]; null clears the decision. */
    fun mark(profile: ProfileRef, videos: Collection<PlatformId>, state: VideoState?, weight: Float = 1f, source: String = MANUAL) {
        val now = clock().epochSecond
        queries.transaction {
            for (video in videos) {
                if (state == null) queries.deleteProfileVideo(profile.id, video.toString())
                else queries.upsertProfileVideo(profile.id, video.toString(), state.name.lowercase(), weight.toDouble(), source, now)
            }
        }
    }

    fun entries(profile: ProfileRef, state: VideoState? = null): List<ProfileEntry> =
        queries.selectProfileVideos(profile.id).executeAsList()
            .map { ProfileEntry(PlatformId.parse(it.video_id), VideoState.valueOf(it.state.uppercase()), it.weight.toFloat(), it.source) }
            .filter { state == null || it.state == state }

    // Feedback on a recommendation (SPEC, Feedback).

    /** "More like this": joins the profile at reduced weight. */
    fun moreLikeThis(profile: ProfileRef, video: PlatformId) =
        mark(profile, listOf(video), VideoState.INCLUDED, MORE_LIKE_THIS_WEIGHT, "more_like_this")

    /** "Not for me": becomes a negative example. */
    fun notForMe(profile: ProfileRef, video: PlatformId) =
        mark(profile, listOf(video), VideoState.NEGATIVE, source = "not_for_me")

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
        const val MORE_LIKE_THIS_WEIGHT = 0.5f
    }
}
