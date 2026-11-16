package forage.core.metadata

import forage.core.PlatformId
import forage.core.source.ExtractorSource
import forage.core.source.SourceException
import forage.core.source.VideoDetails
import java.time.Duration

/** How politely and how stubbornly to fetch. Defaults follow the SPEC's "conservative request rates". */
data class FetchPolicy(
    /** Wait between any two requests. */
    val pause: Duration = Duration.ofMillis(1500),
    /** Requests per video before giving up on it. */
    val attempts: Int = 4,
    /** Wait after the first failed attempt; doubles after each further failure. */
    val firstBackoff: Duration = Duration.ofSeconds(10),
    /** In a bulk run, stop after this many videos in a row failed every attempt: the platform is blocking us. */
    val stopAfterFailedVideos: Int = 3,
    /** Cached entries older than this are fetched again. Null keeps them forever. */
    val maxAge: Duration? = null,
)

sealed interface FetchOutcome {
    data class Cached(val details: VideoDetails) : FetchOutcome
    data class Fetched(val details: VideoDetails) : FetchOutcome
    data class Unavailable(val reason: String) : FetchOutcome
    data class Failed(val error: SourceException) : FetchOutcome
}

data class FetchSummary(
    val cached: Int,
    val fetched: Int,
    val unavailable: Int,
    val failed: Int,
    /** Videos left untouched because the run stopped early. */
    val notAttempted: Int,
) {
    val stoppedEarly get() = notAttempted > 0
}

/** Reads through the cache; only videos it has never seen (or that went stale) reach the network. */
class MetadataFetcher(
    private val source: ExtractorSource,
    private val cache: MetadataCache,
    private val policy: FetchPolicy = FetchPolicy(),
    private val sleep: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) {
    private var requested = false

    fun fetch(id: PlatformId): FetchOutcome {
        cache.video(id, policy.maxAge)?.let { return FetchOutcome.Cached(it) }
        cache.unavailableReason(id)?.let { return FetchOutcome.Unavailable(it) }

        var backoff = policy.firstBackoff
        var lastError: SourceException? = null
        repeat(policy.attempts) { attempt ->
            if (attempt > 0) {
                sleep(backoff)
                backoff = backoff.multipliedBy(2)
            } else if (requested) {
                sleep(policy.pause)
            }
            requested = true
            try {
                val details = source.video(id)
                cache.put(details)
                return FetchOutcome.Fetched(details)
            } catch (e: SourceException.NotAvailable) {
                val reason = e.message ?: "not available"
                cache.putUnavailable(id, reason)
                return FetchOutcome.Unavailable(reason)
            } catch (e: SourceException) {
                lastError = e
            }
        }
        return FetchOutcome.Failed(lastError!!)
    }

    /** Fetches [ids] in order, reporting each outcome as it happens. Safe to re-run: finished videos come from the cache. */
    fun fetchAll(ids: List<PlatformId>, onOutcome: (PlatformId, FetchOutcome) -> Unit = { _, _ -> }): FetchSummary {
        var cached = 0
        var fetched = 0
        var unavailable = 0
        var failed = 0
        var failedInARow = 0
        var attempted = 0
        for (id in ids) {
            if (failedInARow >= policy.stopAfterFailedVideos) break
            val outcome = fetch(id)
            attempted++
            when (outcome) {
                is FetchOutcome.Cached -> cached++
                is FetchOutcome.Fetched -> fetched++
                is FetchOutcome.Unavailable -> unavailable++
                is FetchOutcome.Failed -> failed++
            }
            // Cache hits say nothing about the network, so they don't reset the streak.
            when (outcome) {
                is FetchOutcome.Failed -> failedInARow++
                is FetchOutcome.Fetched, is FetchOutcome.Unavailable -> failedInARow = 0
                is FetchOutcome.Cached -> {}
            }
            onOutcome(id, outcome)
        }
        return FetchSummary(cached, fetched, unavailable, failed, notAttempted = ids.size - attempted)
    }
}
