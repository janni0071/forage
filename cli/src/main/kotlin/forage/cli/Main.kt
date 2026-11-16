package forage.cli

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import forage.core.db.ForageDatabase
import forage.core.importer.ImportResult
import forage.core.importer.TakeoutCsv
import forage.core.importer.TakeoutWatchHistory
import forage.core.importer.TakeoutWatchHistoryHtml
import forage.core.importer.WatchEvent
import forage.core.library.HistoryFilter
import forage.core.library.WatchHistory
import forage.core.metadata.FetchOutcome
import forage.core.metadata.MetadataCache
import forage.core.metadata.MetadataFetcher
import forage.core.profile.ProfileFile
import forage.core.source.SourceException
import forage.core.source.VideoStub
import forage.source.youtube.YouTubeSource
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Properties
import kotlin.system.exitProcess

private const val USAGE = """Usage:
  forage takeout <file> [--top N]
      Summarise a Google Takeout video list. <file> is watch-history.json, watch-history.html
      or a playlist such as "playlists/Watch later-videos.csv".

  forage pick <file> [filters] [--limit N] [--db FILE]
      Print matching videos as profile lines, most watched first. Append them to your profile:
        forage pick watch-history.html --channel "Workshop Wren" >> profile.txt
      Playlists list ids only; titles are filled in from the metadata cache once fetched.
      Filters (combined with AND; --channel may be repeated and matches any):
        --channel TEXT       channel name contains TEXT, or a full channel id (youtube:UC...)
        --title TEXT         title contains TEXT
        --min-watches N      watched at least N times
        --since YYYY-MM-DD   watched on or after this day
        --until YYYY-MM-DD   watched on or before this day

  forage profile <profile.txt>
      Check a profile file: count videos, report duplicates and unreadable lines.

  forage fetch <profile.txt | url-or-id> [--db FILE]
      Fetch metadata and related videos from YouTube into the local cache (needs network).
      With a profile file, fetches every video in it; cached videos are skipped, so an
      interrupted or blocked run can simply be started again. Default cache: data/forage.db

  forage search <query>
      Search YouTube for videos (needs network)."""

fun main(args: Array<String>) {
    // Titles are multilingual; don't let a non-UTF-8 default console charset mangle them.
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8))
    try {
        val rest = args.drop(1)
        when (args.firstOrNull()) {
            "takeout" -> takeout(Args(rest, setOf("--top")))
            "pick" -> pick(Args(rest, setOf("--channel", "--title", "--min-watches", "--since", "--until", "--limit", "--db")))
            "profile" -> profile(Args(rest, emptySet()))
            "fetch" -> fetch(Args(rest, setOf("--db")))
            "search" -> search(Args(rest, emptySet()))
            else -> throw UsageError(USAGE)
        }
    } catch (e: UsageError) {
        System.err.println(e.message)
        exitProcess(2)
    } catch (e: SourceException) {
        System.err.println(e.message)
        exitProcess(if (e is SourceException.Blocked) 3 else 1)
    }
}

private fun takeout(args: Args) {
    val top = args.int("--top") ?: 15
    val result = readHistory(args)
    val videos = WatchHistory.select(result.events)
    val dates = result.events.mapNotNull { it.watchedAt }

    println("Watch events:   ${result.events.size}")
    println("Unique videos:  ${videos.size} (${videos.count { it.isShort }} Shorts)")
    println("Channels:       ${videos.mapNotNull { it.channel?.toString() ?: it.channelName }.distinct().size}")
    if (dates.isNotEmpty()) println("Date range:     ${dates.min()} to ${dates.max()}")
    for ((reason, count) in result.skipped) println("Skipped:        $count ${reason.name.lowercase().replace('_', ' ')}")

    println("\nTop channels by videos watched:")
    videos.groupingBy { it.channelName ?: "(unknown)" }.eachCount()
        .entries.sortedByDescending { it.value }.take(top)
        .forEach { println("  %5d  %s".format(it.value, it.key)) }

    println("\nMost rewatched:")
    videos.filter { it.watchCount > 1 }.take(top).forEach { println("  " + ProfileFile.line(it)) }
}

private fun pick(args: Args) {
    val zone = ZoneId.systemDefault()
    val filter = HistoryFilter(
        channels = args.all("--channel"),
        title = args.one("--title"),
        since = args.one("--since")?.let { day(it).atStartOfDay(zone).toInstant() },
        // --until is inclusive for the user, so the cut-off is the start of the next day.
        until = args.one("--until")?.let { day(it).plusDays(1).atStartOfDay(zone).toInstant() },
        minWatches = args.int("--min-watches") ?: 1,
    )
    // Playlists and old history entries carry no titles; borrow them from the metadata cache where it has them.
    val cache = args.one("--db").let { File(it ?: "data/forage.db") }.takeIf { it.isFile }?.let { MetadataCache(openDatabase(it)) }
    val events = readHistory(args).events.map { e ->
        if (e.title != null || cache == null) e
        else cache.video(e.video)?.metadata?.let { e.copy(title = it.title, channel = it.channel, channelName = it.channelName) } ?: e
    }
    val matches = WatchHistory.select(events, filter)
    val shown = matches.take(args.int("--limit") ?: Int.MAX_VALUE)
    shown.forEach { println(ProfileFile.line(it)) }
    // Status goes to stderr so that stdout can be redirected straight into a profile file.
    System.err.println("${shown.size} of ${matches.size} matching videos")
}

private fun profile(args: Args) {
    val parsed = ProfileFile.parse(file(args).readText())
    println("Videos:      ${parsed.videos.size}")
    println("Duplicates:  ${parsed.duplicates.size}")
    parsed.duplicates.distinct().forEach { println("  $it") }
    println("Unreadable:  ${parsed.invalid.size}")
    parsed.invalid.forEach { (line, text) -> println("  line $line: $text") }
    if (parsed.invalid.isNotEmpty()) exitProcess(1)
}

private fun fetch(args: Args) {
    val arg = args.positional.singleOrNull() ?: throw UsageError(USAGE)
    val cache = MetadataCache(openDatabase(File(args.one("--db") ?: "data/forage.db")))
    val fetcher = MetadataFetcher(YouTubeSource(), cache)
    if (File(arg).isFile) fetchProfile(File(arg), fetcher) else fetchOne(arg, fetcher)
}

private fun fetchOne(arg: String, fetcher: MetadataFetcher) {
    // Accept a bare YouTube id too, the form people copy most often.
    val entry = if (Regex("[A-Za-z0-9_-]{11}").matches(arg)) "youtube:$arg" else arg
    val id = ProfileFile.parse(entry).videos.singleOrNull() ?: throw UsageError("Not a file, video URL or id: $arg")
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

private fun fetchProfile(file: File, fetcher: MetadataFetcher) {
    val parsed = ProfileFile.parse(file.readText())
    if (parsed.invalid.isNotEmpty()) throw UsageError("${file.path} has unreadable lines; run 'forage profile ${file.path}'")
    val total = parsed.videos.size
    var done = 0
    val summary = fetcher.fetchAll(parsed.videos) { id, outcome ->
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

private fun openDatabase(file: File): ForageDatabase {
    file.absoluteFile.parentFile.mkdirs()
    // The driver creates the schema on first use and migrates it on later versions.
    return ForageDatabase(JdbcSqliteDriver("jdbc:sqlite:${file.path}", Properties(), ForageDatabase.Schema))
}

private fun search(args: Args) {
    if (args.positional.isEmpty()) throw UsageError(USAGE)
    YouTubeSource().search(args.positional.joinToString(" ")).forEach(::printStub)
}

private fun printStub(stub: VideoStub) =
    println("  ${stub.id}  ${stub.title} — ${stub.channelName ?: "?"}${if (stub.isShort) " [Short]" else ""}")

/** Reads any Takeout video list: watch history as JSON or HTML, or a playlist's `-videos.csv`. */
private fun readHistory(args: Args): ImportResult {
    val file = file(args)
    return when (file.extension.lowercase()) {
        "json" -> file.inputStream().buffered().use(TakeoutWatchHistory::parse)
        "html", "htm" -> file.inputStream().buffered().use(TakeoutWatchHistoryHtml::parse)
        // A playlist only lists ids; "watched at" stands in for "added at" so the date filters work on it.
        "csv" -> ImportResult(
            TakeoutCsv.playlist(file.readText()).map { WatchEvent(it.video, null, null, null, it.addedAt) },
            emptyMap(),
        )
        else -> throw UsageError("Don't know how to read ${file.name}: expected watch-history.json, watch-history.html or a playlist .csv")
    }
}

private fun file(args: Args): File {
    val path = args.positional.singleOrNull() ?: throw UsageError(USAGE)
    return File(path).takeIf { it.isFile } ?: throw UsageError("No such file: $path")
}

private fun day(text: String): LocalDate = try {
    LocalDate.parse(text)
} catch (e: DateTimeParseException) {
    throw UsageError("Expected a date like 2026-01-31, got '$text'")
}
