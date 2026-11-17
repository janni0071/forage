package forage.core.recommend

import forage.core.PlatformId

/** A video the user put into the profile. The profile is this set of vectors, never their average. */
data class ProfileVideo(
    val id: PlatformId,
    val title: String,
    val channel: PlatformId?,
    val vector: FloatArray,
    /** 1 for videos included on purpose; lower for "more like this" feedback. */
    val weight: Float = 1f,
    /**
     * Days between the video's upload and the user saving or watching it, when both are known.
     * Small values mean the user follows this kind of video as it comes out.
     */
    val ageWhenAddedDays: Long? = null,
) {
    val addedFresh get() = ageWhenAddedDays != null && ageWhenAddedDays <= FRESH_DAYS

    companion object {
        const val FRESH_DAYS = 30L
    }
}

/**
 * One isolated taste profile (SPEC: profiles never influence each other).
 * [negatives] are the vectors of videos marked "not for me".
 */
class Profile(val videos: List<ProfileVideo>, val negatives: List<FloatArray> = emptyList())

/** A topic within the profile: videos that resemble each other, with words that describe them. */
data class Cluster(
    val index: Int,
    val videos: List<ProfileVideo>,
    val centroid: FloatArray,
    val keywords: List<String>,
    /** True for the catch-all of one-off interests, which share no topic and have no keywords. */
    val isVaried: Boolean = false,
    val preference: TopicPreference? = null,
) {
    val label get() = if (isVaried) "varied interests" else keywords.take(3).joinToString(", ").ifEmpty { "topic ${index + 1}" }
}
