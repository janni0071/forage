package forage.cli

import forage.core.importer.ImportResult
import forage.core.importer.TakeoutWatchHistory
import forage.core.library.HistoryFilter
import forage.core.library.WatchHistory
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
import kotlin.system.exitProcess

private const val USAGE = """Usage:
  forage takeout <watch-history.json> [--top N]
      Summarise a Google Takeout watch history.

  forage pick <watch-history.json> [filters] [--limit N]
      Print matching videos as profile lines, most watched first. Append them to your profile:
        forage pick history.json --channel "Workshop Wren" >> profile.txt
      Filters (combined with AND; --channel may be repeated and matches any):
        --channel TEXT       channel name contains TEXT, or a full channel id (youtube:UC...)
        --title TEXT         title contains TEXT
        --min-watches N      watched at least N times
        --since YYYY-MM-DD   watched on or after this day
        --until YYYY-MM-DD   watched on or before this day

  forage profile <profile.txt>
      Check a profile file: count videos, report duplicates and unreadable lines.

  forage fetch <url-or-id>
      Fetch one video's metadata and related videos from YouTube (needs network).

  forage search <query>
      Search YouTube for videos (needs network)."""

fun main(args: Array<String>) {
    // Titles are multilingual; don't let a non-UTF-8 default console charset mangle them.
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8))
    try {
        val rest = args.drop(1)
        when (args.firstOrNull()) {
            "takeout" -> takeout(Args(rest, setOf("--top")))
            "pick" -> pick(Args(rest, setOf("--channel", "--title", "--min-watches", "--since", "--until", "--limit")))
            "profile" -> profile(Args(rest, emptySet()))
            "fetch" -> fetch(Args(rest, emptySet()))
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
    val matches = WatchHistory.select(readHistory(args).events, filter)
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
    // Accept a bare YouTube id too, the form people copy most often.
    val entry = if (Regex("[A-Za-z0-9_-]{11}").matches(arg)) "youtube:$arg" else arg
    val id = ProfileFile.parse(entry).videos.singleOrNull() ?: throw UsageError("Not a video URL or id: $arg")
    val (video, related) = YouTubeSource().video(id)

    println("${video.id}  ${video.title}")
    println("Channel:      ${video.channelName ?: "?"} (${video.channel ?: "no id"})")
    println("Uploaded:     ${video.uploadedAt ?: "?"}   Duration: ${video.durationSeconds ?: "?"}s   Short: ${video.isShort}")
    println("Language:     ${video.language ?: "?"}   ${video.extra}")
    println("Tags:         ${video.tags.joinToString(", ").ifEmpty { "(none)" }}")
    println("Description:  ${video.description.length} chars: ${video.description.replace(Regex("\\s+"), " ").take(160)}")
    println("\nRelated videos: ${related.size}")
    related.take(10).forEach(::printStub)
}

private fun search(args: Args) {
    if (args.positional.isEmpty()) throw UsageError(USAGE)
    YouTubeSource().search(args.positional.joinToString(" ")).forEach(::printStub)
}

private fun printStub(stub: VideoStub) =
    println("  ${stub.id}  ${stub.title} — ${stub.channelName ?: "?"}${if (stub.isShort) " [Short]" else ""}")

private fun readHistory(args: Args): ImportResult = file(args).inputStream().buffered().use(TakeoutWatchHistory::parse)

private fun file(args: Args): File {
    val path = args.positional.singleOrNull() ?: throw UsageError(USAGE)
    return File(path).takeIf { it.isFile } ?: throw UsageError("No such file: $path")
}

private fun day(text: String): LocalDate = try {
    LocalDate.parse(text)
} catch (e: DateTimeParseException) {
    throw UsageError("Expected a date like 2026-01-31, got '$text'")
}
