package forage.cli

import forage.core.importer.TakeoutWatchHistory
import forage.core.importer.WatchEvent
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

private const val USAGE = """Usage:
  forage takeout <watch-history.json> [--top N]   Summarise a Google Takeout watch history"""

fun main(args: Array<String>) {
    // Titles are multilingual; don't let a non-UTF-8 default console charset mangle them.
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8))
    when (args.firstOrNull()) {
        "takeout" -> takeout(args.drop(1))
        else -> fail(USAGE)
    }
}

private fun takeout(args: List<String>) {
    val topIndex = args.indexOf("--top")
    val top = if (topIndex >= 0) args.getOrNull(topIndex + 1)?.toIntOrNull() ?: fail(USAGE) else 15
    val path = args.filterIndexed { i, _ -> topIndex < 0 || (i != topIndex && i != topIndex + 1) }
        .singleOrNull() ?: fail(USAGE)
    val file = File(path)
    if (!file.isFile) fail("No such file: $path")

    val result = file.inputStream().buffered().use(TakeoutWatchHistory::parse)
    val events = result.events
    val byVideo = events.groupBy { it.video }
    val dates = events.mapNotNull { it.watchedAt }

    println("Watch events:   ${events.size}")
    println("Unique videos:  ${byVideo.size} (${byVideo.count { (_, e) -> e.any(WatchEvent::isShort) }} Shorts)")
    println("Channels:       ${events.mapNotNull { it.channel ?: it.channelName }.distinct().size}")
    if (dates.isNotEmpty()) println("Date range:     ${dates.min()} to ${dates.max()}")
    for ((reason, count) in result.skipped) println("Skipped:        $count ${reason.name.lowercase().replace('_', ' ')}")

    println("\nTop channels by videos watched:")
    byVideo.values.map { it.first() }
        .groupingBy { it.channelName ?: "(unknown)" }.eachCount()
        .entries.sortedByDescending { it.value }.take(top)
        .forEach { println("  %5d  %s".format(it.value, it.key)) }

    println("\nMost rewatched:")
    byVideo.values.filter { it.size > 1 }.sortedByDescending { it.size }.take(top)
        .forEach { e ->
            val v = e.first()
            println("  %5d  %s  %s — %s".format(e.size, v.video, v.title ?: "(no title)", v.channelName ?: "?"))
        }
}

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(2)
}
