package forage.cli

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import forage.core.PlatformId
import forage.core.db.ForageDatabase
import forage.core.embedding.EmbeddingStore
import forage.core.embedding.EmbeddingText
import forage.core.embedding.Vectors
import forage.core.importer.TakeoutFolder
import forage.core.library.HistoryFilter
import forage.core.library.LibraryStore
import forage.core.library.WatchHistory
import forage.core.metadata.FetchOutcome
import forage.core.metadata.FetchPolicy
import forage.core.metadata.MetadataCache
import forage.core.metadata.MetadataFetcher
import forage.core.metadata.SearchCache
import forage.core.profile.ProfileFile
import forage.core.profile.ProfileRef
import forage.core.profile.ProfileStore
import forage.core.profile.VideoState
import forage.core.recommend.Discover
import forage.core.recommend.EmptyProfileException
import forage.core.recommend.RankingConfig
import forage.core.recommend.TopicPreference
import forage.core.recommend.TopicStore
import forage.core.source.SourceException
import forage.core.source.VideoStub
import forage.embedding.onnx.ModelFiles
import forage.embedding.onnx.ModelSpec
import forage.embedding.onnx.OnnxEmbedder
import forage.language.lingua.LinguaDetector
import forage.source.youtube.YouTubeSource
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Properties
import kotlin.system.exitProcess

private const val USAGE = """Usage: forage <command> [options]

Library
  import <takeout-folder>      Import an extracted Google Takeout: watch history, playlists and
                               subscriptions. Nothing imported enters a profile by itself.
  library [selection]          List videos from the library with their state in the profile.
  status                       Show what the vault and the profile contain.

Profile
  include [videos] [selection] Put videos into the profile.
  exclude [videos] [selection] Keep videos out of the profile and out of recommendations.
  reject  [videos] [selection] Mark videos "not for me": steers recommendations away from them.
  clear   [videos] [selection] Undo any of the three above.

  [videos] are URLs or ids, and/or --file FILE with one per line.
  [selection] picks videos from the library; filters combine with AND:
    --playlist NAME      videos of an imported playlist, not the watch history
    --channel TEXT       channel name contains TEXT, or a channel id (repeatable, any matches)
    --title TEXT         title contains TEXT
    --min-watches N      watched at least N times
    --since / --until YYYY-MM-DD   watched or saved in this period
    --limit N            the N most watched, then most recent

Recommendations
  fetch [video] [--max-age HOURS]
                               Fetch metadata and related videos from YouTube (needs network).
                               Without a video: everything in the profile that isn't cached, or
                               whose cached copy is older than --max-age. Safe to re-run.
  discover [--count N] [--no-search] [--shorts]
                               Recommend videos for the profile, each with its reason.
  more <n> | no <n> | save <n> Feedback on pick number n of the last discover: more like this,
                               not for me, or save to watch later.
  later [--remove VIDEO]       Show the watch-later queue, or take a video off it.

Tools
  similar <video> [--top N]    List cached videos most similar to one.
  search <query>               Search YouTube.

Common options
  --profile NAME               Profile to use (default "Default"; created on first use)
  --db FILE                    The vault's database (default data/forage.db)
  --models DIR                 Where the embedding model is kept (default data/models)"""

private val COMMON = setOf("--db", "--profile", "--models")
private val SELECTION = setOf("--playlist", "--channel", "--title", "--min-watches", "--since", "--until", "--limit", "--file")

fun main(args: Array<String>) {
    // Titles are multilingual; don't let a non-UTF-8 default console charset mangle them.
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8))
    try {
        val rest = args.drop(1)
        when (val command = args.firstOrNull()) {
            "import" -> import(Args(rest, COMMON))
            "library" -> library(Args(rest, COMMON + SELECTION))
            "status" -> status(Args(rest, COMMON))
            "include" -> mark(Args(rest, COMMON + SELECTION), VideoState.INCLUDED)
            "exclude" -> mark(Args(rest, COMMON + SELECTION), VideoState.EXCLUDED)
            "reject" -> mark(Args(rest, COMMON + SELECTION), VideoState.NEGATIVE)
            "clear" -> mark(Args(rest, COMMON + SELECTION), null)
            "fetch" -> fetch(Args(rest, COMMON + "--max-age"))
            "discover" -> discover(Args(rest, COMMON + "--count", setOf("--no-search", "--shorts")))
            "more", "no", "save" -> feedback(command, Args(rest, COMMON))
            "later" -> later(Args(rest, COMMON + "--remove"))
            "similar" -> similar(Args(rest, COMMON + "--top"))
            "search" -> search(Args(rest, emptySet()))
            else -> throw UsageError(USAGE)
        }
    } catch (e: UsageError) {
        System.err.println(e.message)
        exitProcess(2)
    } catch (e: EmptyProfileException) {
        System.err.println("${e.message}. Add videos with 'forage include', then run 'forage fetch'.")
        exitProcess(1)
    } catch (e: SourceException) {
        System.err.println(e.message)
        exitProcess(if (e is SourceException.Blocked) 3 else 1)
    }
}

/** One vault: a database file and the stores on top of it. */
private class Vault(args: Args) {
    val db = openDatabase(File(args.one("--db") ?: "data/forage.db"))
    val cache = MetadataCache(db)
    val library = LibraryStore(db)
    val profiles = ProfileStore(db)
    val profile: ProfileRef = profiles.profile(args.one("--profile") ?: ProfileStore.DEFAULT)

    fun states(): Map<PlatformId, VideoState> = profiles.entries(profile).associate { it.video to it.state }

    /** The best title known for a video: fetched metadata first, then what an import said. */
    fun label(id: PlatformId): String {
        val meta = cache.video(id)?.metadata
        val title = meta?.title ?: library.video(id)?.title
        val channel = meta?.channelName ?: library.video(id)?.channelName
        return listOfNotNull(title, channel).joinToString(" — ").ifEmpty { "(no title yet)" }
    }
}

private fun openDatabase(file: File): ForageDatabase {
    file.absoluteFile.parentFile.mkdirs()
    // The driver creates the schema on first use and migrates it on later versions.
    return ForageDatabase(JdbcSqliteDriver("jdbc:sqlite:${file.path}", Properties(), ForageDatabase.Schema))
}

private fun embedder(args: Args): OnnxEmbedder {
    val spec = ModelSpec.MULTILINGUAL_MINILM
    val files = ModelFiles.ensure(spec, Path.of(args.one("--models") ?: "data/models", "minilm")) {
        System.err.println("Downloading $it from ${spec.repo} ...")
    }
    return OnnxEmbedder(spec, files)
}

// Library

private fun import(args: Args) {
    val dir = File(args.positional.singleOrNull() ?: throw UsageError(USAGE))
    if (!dir.isDirectory) throw UsageError("No such folder: $dir (extract the Takeout archive first)")
    val data = TakeoutFolder.read(dir)
    val vault = Vault(args)
    val summary = vault.library.importTakeout(data)

    println("Watch history:  ${summary.watchEvents} events")
    for ((reason, count) in data.history.skipped) println("  skipped $count ${reason.name.lowercase().replace('_', ' ')}")
    println("Playlists:      ${summary.playlists.size}")
    summary.playlists.forEach { (name, size) -> println("  %5d  %s".format(size, name)) }
    println("Subscriptions:  ${summary.subscriptions}")
    val (videos, events) = vault.library.counts()
    println("\nThe library now holds $videos videos and $events watch events. Nothing was added to a profile.")
}

/** Videos named on the command line or in --file, plus those the selection options pick from the library. */
private fun selected(args: Args, vault: Vault, requireSome: Boolean): List<PlatformId> {
    val named = args.positional.map(::videoId) +
        args.one("--file")?.let { path ->
            val file = File(path).takeIf(File::isFile) ?: throw UsageError("No such file: $path")
            val parsed = ProfileFile.parse(file.readText())
            if (parsed.invalid.isNotEmpty()) {
                throw UsageError("$path has unreadable lines:\n" + parsed.invalid.joinToString("\n") { "  line ${it.first}: ${it.second}" })
            }
            parsed.videos
        }.orEmpty()

    val filters = listOf("--playlist", "--channel", "--title", "--min-watches", "--since", "--until", "--limit")
    val filtering = filters.any { args.one(it) != null }
    if (!filtering) {
        if (named.isEmpty() && requireSome) throw UsageError("Name some videos, or select them with --playlist, --channel, --since ...")
        if (named.isNotEmpty() || requireSome) return named.distinct()
    }

    val playlist = args.one("--playlist")
    if (playlist != null && playlist !in vault.library.playlists()) {
        throw UsageError("No playlist \"$playlist\". Imported playlists: ${vault.library.playlists().keys.joinToString { "\"$it\"" }}")
    }
    val zone = ZoneId.systemDefault()
    val filter = HistoryFilter(
        channels = args.all("--channel"),
        title = args.one("--title"),
        since = args.one("--since")?.let { day(it).atStartOfDay(zone).toInstant() },
        // --until is inclusive for the user, so the cut-off is the start of the next day.
        until = args.one("--until")?.let { day(it).plusDays(1).atStartOfDay(zone).toInstant() },
        minWatches = args.int("--min-watches") ?: 1,
    )
    // Imports often lack titles and channels; borrow them from fetched metadata so filters can see them.
    val events = vault.library.events(playlist).map { e ->
        if (e.title != null && e.channelName != null) e
        else vault.cache.video(e.video)?.metadata?.let {
            e.copy(title = e.title ?: it.title, channel = e.channel ?: it.channel, channelName = e.channelName ?: it.channelName)
        } ?: e
    }
    val matches = WatchHistory.select(events, filter).map { it.video }
    return (named + matches.take(args.int("--limit") ?: Int.MAX_VALUE)).distinct()
}

private fun library(args: Args) {
    val vault = Vault(args)
    val videos = selected(args, vault, requireSome = false)
    val states = vault.states()
    videos.forEach { id ->
        val state = states[id]?.let { " [${stateName(it)}]" }.orEmpty()
        println("$id  ${vault.label(id)}$state")
    }
    System.err.println("${videos.size} videos")
}

private fun status(args: Args) {
    val vault = Vault(args)
    val (videos, events) = vault.library.counts()
    println("Library:   $videos videos, $events watch events, ${vault.library.subscriptions().size} subscriptions")
    vault.library.playlists().forEach { (name, size) -> println("  playlist %-28s %5d videos".format("\"$name\"", size)) }
    println("Watch later queue: ${vault.library.watchLater().size} videos")
    println("Metadata cache:    ${vault.cache.size()} videos")
    for (profile in vault.profiles.profiles()) {
        val entries = vault.profiles.entries(profile)
        val marker = if (profile == vault.profile) "*" else " "
        println(
            "$marker Profile \"${profile.name}\": ${entries.count { it.state == VideoState.INCLUDED }} included, " +
                "${entries.count { it.state == VideoState.EXCLUDED }} excluded, ${entries.count { it.state == VideoState.NEGATIVE }} not for me"
        )
    }
}

// Profile

private fun mark(args: Args, state: VideoState?) {
    val vault = Vault(args)
    val videos = selected(args, vault, requireSome = true)
    videos.forEach { vault.library.remember(it) }
    vault.profiles.mark(vault.profile, videos, state)
    val what = state?.let { "marked ${stateName(it)}" } ?: "cleared"
    println("${videos.size} videos $what in profile \"${vault.profile.name}\"")
}

private fun stateName(state: VideoState) = when (state) {
    VideoState.INCLUDED -> "in profile"
    VideoState.EXCLUDED -> "excluded"
    VideoState.NEGATIVE -> "not for me"
}

// Recommendations

private fun fetch(args: Args) {
    val vault = Vault(args)
    val policy = FetchPolicy(maxAge = args.int("--max-age")?.let { Duration.ofHours(it.toLong()) })
    val fetcher = MetadataFetcher(YouTubeSource(), vault.cache, policy)
    val single = args.positional.singleOrNull()
    if (single != null) return fetchOne(videoId(single), fetcher)
    if (args.positional.isNotEmpty()) throw UsageError(USAGE)

    // Rejected videos need metadata too: they only steer recommendations once embedded.
    val wanted = vault.profiles.entries(vault.profile).filter { it.state != VideoState.EXCLUDED }.map { it.video }
    if (wanted.isEmpty()) throw EmptyProfileException("The profile \"${vault.profile.name}\" has no videos yet")
    val total = wanted.size
    var done = 0
    val summary = fetcher.fetchAll(wanted) { id, outcome ->
        done++
        val text = when (outcome) {
            is FetchOutcome.Cached -> return@fetchAll
            is FetchOutcome.Fetched -> "ok       ${outcome.details.metadata.title}"
            is FetchOutcome.Unavailable -> "gone     ${outcome.reason}"
            is FetchOutcome.Failed -> "FAILED   ${outcome.error.message}"
        }
        println("[%d/%d] %s  %s".format(done, total, id, text))
    }
    println(
        "\nFetched ${summary.fetched}, already cached ${summary.cached}, " +
            "gone ${summary.unavailable}, failed ${summary.failed}, not attempted ${summary.notAttempted}"
    )
    if (summary.stoppedEarly) {
        System.err.println("Stopped early: YouTube kept refusing requests. Run the same command again later to continue.")
        exitProcess(3)
    }
    if (summary.failed > 0) exitProcess(1)
}

private fun fetchOne(id: PlatformId, fetcher: MetadataFetcher) {
    val (video, related) = when (val outcome = fetcher.fetch(id)) {
        is FetchOutcome.Cached -> outcome.details.also { println("(from cache)") }
        is FetchOutcome.Fetched -> outcome.details
        is FetchOutcome.Unavailable -> throw SourceException.NotAvailable("$id is not available: ${outcome.reason}")
        is FetchOutcome.Failed -> throw outcome.error
    }
    println("${video.id}  ${video.title}")
    println("Channel:      ${video.channelName ?: "?"} (${video.channel ?: "no id"})")
    println("Uploaded:     ${video.uploadedAt ?: "?"}   Duration: ${video.durationSeconds ?: "?"}s   Short: ${video.isShort}")
    println("Language:     ${video.language ?: "?"}   ${video.extra}")
    println("Tags:         ${video.tags.joinToString(", ").ifEmpty { "(none)" }}")
    println("Description:  ${video.description.length} chars: ${video.description.replace(Regex("\\s+"), " ").take(160)}")
    println("\nRelated videos: ${related.size}")
    related.take(10).forEach(::printStub)
}

private fun discover(args: Args) {
    if (args.positional.isNotEmpty()) throw UsageError(USAGE)
    val vault = Vault(args)
    val config = RankingConfig(showShorts = args.flag("--shorts")).let { c -> args.int("--count")?.let { c.copy(slots = it) } ?: c }
    val result = embedder(args).use { embedder ->
        Discover(
            vault.cache, vault.library, vault.profiles, EmbeddingStore(vault.db, embedder),
            detector = LinguaDetector(),
            source = YouTubeSource().takeUnless { args.flag("--no-search") },
            config = config,
            searchCache = SearchCache(vault.db),
            topics = TopicStore(vault.db),
        ).run(vault.profile)
    }

    System.err.println("Timings (ms): " + result.timings.entries.joinToString { "${it.key} ${it.value}" })
    if (result.withoutMetadata > 0) System.err.println("${result.withoutMetadata} profile videos have no metadata yet and were left out; run 'forage fetch'")
    result.searchFailures.forEach { System.err.println("Search skipped: $it") }

    val topics = result.clusters.filter { !it.isVaried }
    println("Profile \"${vault.profile.name}\": ${result.clusters.sumOf { it.videos.size }} videos, ${topics.size} topics")
    result.clusters.forEach { c ->
        val pref = when (c.preference) { TopicPreference.PINNED -> "  pinned"; TopicPreference.MUTED -> "  muted"; null -> "" }
        println("  %2d. %-42s %3d videos%s".format(c.index + 1, c.label, c.videos.size, pref))
    }
    println(
        "\n${result.picks.size} picks from ${result.candidates} candidates" +
            if (result.foreignLanguage > 0) " (${result.foreignLanguage} more dropped for their language)" else ""
    )
    println()
    printPicks(vault)
    println("Feedback: 'forage more <n>', 'forage no <n>' or 'forage save <n>'")
}

private fun printPicks(vault: Vault) {
    val states = vault.states()
    val saved = vault.library.watchLater().toSet()
    for (pick in vault.profiles.picks(vault.profile)) {
        val done = when {
            pick.video in saved -> "  [saved]"
            states[pick.video] == VideoState.INCLUDED -> "  [more like this]"
            states[pick.video] == VideoState.NEGATIVE -> "  [not for me]"
            else -> ""
        }
        println("%2d. %s — %s%s".format(pick.position, pick.title, pick.channelName ?: "?", done))
        println("    https://www.youtube.com/watch?v=${pick.video.id}   score %.2f".format(pick.score))
        println("    Why: ${pick.reason}")
        println("    Found via: ${pick.foundVia}\n")
    }
}

private fun feedback(kind: String, args: Args) {
    val vault = Vault(args)
    val picks = vault.profiles.picks(vault.profile)
    if (picks.isEmpty()) throw UsageError("No picks yet for profile \"${vault.profile.name}\"; run 'forage discover' first")
    val numbers = args.positional.map { it.toIntOrNull() ?: throw UsageError("'$it' is not a pick number") }
    if (numbers.isEmpty()) throw UsageError("Which pick? For example: forage $kind 3")
    for (n in numbers) {
        val pick = picks.firstOrNull { it.position == n } ?: throw UsageError("There is no pick $n; the last batch has ${picks.size}")
        // Keep what the list result told us, so the video has a title before any metadata fetch.
        vault.library.remember(pick.video, pick.title, pick.channel, pick.channelName)
        val done = when (kind) {
            "more" -> { vault.profiles.moreLikeThis(vault.profile, pick.video); "added to the profile at reduced weight" }
            "no" -> { vault.profiles.notForMe(vault.profile, pick.video); "marked not for me" }
            else -> { vault.library.saveForLater(pick.video); "saved to watch later" }
        }
        println("$n. ${pick.title}: $done")
    }
    if (kind != "save") println("Run 'forage fetch' so this counts in the next discover.")
}

private fun later(args: Args) {
    val vault = Vault(args)
    args.one("--remove")?.let { vault.library.removeFromWatchLater(videoId(it)) }
    val queue = vault.library.watchLater()
    queue.forEach { println("https://www.youtube.com/watch?v=${it.id}  ${vault.label(it)}") }
    System.err.println("${queue.size} videos saved for later")
}

// Tools

private fun similar(args: Args) {
    val id = videoId(args.positional.singleOrNull() ?: throw UsageError(USAGE))
    val vault = Vault(args)
    val texts = vault.cache.ids().associateWith { EmbeddingText.of(vault.cache.video(it)!!.metadata) }
    val vectors = embedder(args).use { EmbeddingStore(vault.db, it).embed(texts) }
    val target = vectors[id] ?: throw UsageError("$id is not in the cache; run 'forage fetch $id' first")
    println("Most similar to: ${vault.label(id)}\n")
    vectors.filterKeys { it != id }
        .mapValues { Vectors.dot(target, it.value) }
        .entries.sortedByDescending { it.value }
        .take(args.int("--top") ?: 10)
        .forEach { (other, score) -> println("  %.3f  %s  %s".format(score, other, vault.label(other))) }
}

private fun search(args: Args) {
    if (args.positional.isEmpty()) throw UsageError(USAGE)
    YouTubeSource().search(args.positional.joinToString(" ")).forEach(::printStub)
}

private fun printStub(stub: VideoStub) =
    println("  ${stub.id}  ${stub.title} — ${stub.channelName ?: "?"}${if (stub.isShort) " [Short]" else ""}")

/** A single video given as URL, `platform:id` or bare YouTube id, the form people copy most often. */
private fun videoId(arg: String): PlatformId {
    val entry = if (Regex("[A-Za-z0-9_-]{11}").matches(arg)) "youtube:$arg" else arg
    return ProfileFile.parse(entry).videos.singleOrNull() ?: throw UsageError("Not a video URL or id: $arg")
}

private fun day(text: String): LocalDate = try {
    LocalDate.parse(text)
} catch (e: DateTimeParseException) {
    throw UsageError("Expected a date like 2026-01-31, got '$text'")
}
