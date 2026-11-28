package forage.core.backup

import forage.core.PlatformId
import forage.core.Platforms
import forage.core.library.LibraryStore
import forage.core.profile.ProfileStore
import forage.core.profile.VideoState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * The library in forms other people and programs read: everything as plain JSON (what's liked,
 * the history, playlists, the queue, subscriptions, where videos were left off), and the YouTube
 * subscriptions in NewPipe's format, which NewPipe, FreeTube and LibreTube all import. Unlike a
 * backup, these are for reading and moving elsewhere, not for restoring Forage.
 */
object Export {
    /** Everything in the library as readable JSON; [watchUrl] gives each video's link. */
    fun libraryJson(library: LibraryStore, profiles: ProfileStore, watchUrl: (PlatformId) -> String?, now: Instant = Instant.now()): String {
        val known = library.allVideos().associateBy { it.id }
        fun video(id: PlatformId) = buildJsonObject {
            put("id", id.toString())
            runCatching { watchUrl(id) }.getOrNull()?.let { put("url", it) }
            known[id]?.let { v ->
                v.title?.let { put("title", it) }
                v.channelName?.let { put("channel", it) }
                v.channel?.let { put("channelId", it.toString()) }
                v.durationSeconds?.let { put("durationSeconds", it) }
                v.uploadedAt?.let { put("uploadedAt", it.toString()) }
            }
        }
        val json = buildJsonObject {
            put("exportedBy", "Forage")
            put("exportedAt", now.toString())
            put("profiles", buildJsonArray {
                for (profile in profiles.profiles()) add(buildJsonObject {
                    put("name", profile.name)
                    val entries = profiles.entries(profile)
                    for ((state, key) in listOf(VideoState.LIKED to "liked", VideoState.NOT_INTERESTED to "notInterested")) {
                        put(key, JsonArray(entries.filter { it.state == state }.sortedByDescending { it.changedAt }.map { e ->
                            JsonObject(video(e.video) + listOfNotNull(e.changedAt?.let { "at" to kotlinx.serialization.json.JsonPrimitive(it.toString()) }))
                        }))
                    }
                })
            })
            put("history", JsonArray(library.events().sortedByDescending { it.watchedAt }.map { e ->
                JsonObject(video(e.video) + listOfNotNull(e.watchedAt?.let { "watchedAt" to kotlinx.serialization.json.JsonPrimitive(it.toString()) }))
            }))
            put("watchLater", JsonArray(library.savedForLater().map { s ->
                JsonObject(video(s.id) + ("savedAt" to kotlinx.serialization.json.JsonPrimitive(s.savedAt.toString())))
            }))
            put("playlists", buildJsonArray {
                for (name in library.playlists().keys) add(buildJsonObject {
                    put("name", name)
                    put("videos", JsonArray(library.playlistItems(name).map { (id, at) ->
                        JsonObject(video(id) + listOfNotNull(at?.let { "addedAt" to kotlinx.serialization.json.JsonPrimitive(it.toString()) }))
                    }))
                })
            })
            put("subscriptions", JsonArray(library.following().map { f ->
                buildJsonObject {
                    put("id", f.channel.toString())
                    f.name?.let { put("name", it) }
                    channelUrl(f.channel)?.let { put("url", it) }
                }
            }))
            put("leftOff", JsonArray(library.playbackPositions().map { (id, p) ->
                JsonObject(video(id) + mapOf("seconds" to kotlinx.serialization.json.JsonPrimitive(p.seconds), "ofSeconds" to kotlinx.serialization.json.JsonPrimitive(p.duration)))
            }))
        }
        return PRETTY.encodeToString(JsonObject.serializer(), json)
    }

    /** The YouTube channels followed, in NewPipe's subscriptions format (NewPipe, FreeTube and LibreTube import it). */
    fun subscriptionsNewPipe(library: LibraryStore): String {
        val json = buildJsonObject {
            put("app_version", "0.27.6")
            put("app_version_int", 1004)
            put("subscriptions", JsonArray(library.following().filter { it.channel.platform == Platforms.YOUTUBE }.map { f ->
                buildJsonObject {
                    put("service_id", 0)
                    put("url", channelUrl(f.channel))
                    put("name", f.name ?: f.channel.id)
                }
            }))
        }
        return PRETTY.encodeToString(JsonObject.serializer(), json)
    }

    /** How many YouTube channels [subscriptionsNewPipe] would hold. */
    fun youTubeSubscriptions(library: LibraryStore): Int = library.following().count { it.channel.platform == Platforms.YOUTUBE }

    private fun channelUrl(channel: PlatformId): String? =
        if (channel.platform == Platforms.YOUTUBE) "https://www.youtube.com/channel/${channel.id}" else null

    private val PRETTY = Json { prettyPrint = true }
}
