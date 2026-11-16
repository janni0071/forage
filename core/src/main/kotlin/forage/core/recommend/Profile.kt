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
)

/**
 * One isolated taste profile (SPEC: profiles never influence each other).
 * [negatives] are the vectors of videos marked "not for me".
 */
class Profile(val videos: List<ProfileVideo>, val negatives: List<FloatArray> = emptyList())

/** A topic within the profile: the videos k-means grouped together, with words that describe them. */
data class Cluster(
    val index: Int,
    val videos: List<ProfileVideo>,
    val centroid: FloatArray,
    val keywords: List<String>,
) {
    val label get() = keywords.take(3).joinToString(", ").ifEmpty { "cluster ${index + 1}" }
}
