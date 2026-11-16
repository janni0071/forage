package forage.core.metadata

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import forage.core.PlatformId
import forage.core.db.ForageDatabase
import forage.core.source.VideoDetails
import forage.core.source.VideoMetadata
import forage.core.source.VideoStub
import java.time.Instant

internal fun memoryDatabase(): ForageDatabase {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    ForageDatabase.Schema.create(driver)
    return ForageDatabase(driver)
}

internal fun details(id: String, related: List<String> = listOf("rel1", "rel2")) = VideoDetails(
    metadata = VideoMetadata(
        id = PlatformId.youtube(id),
        title = "Sauerteig für Anfänger – $id",
        description = "Line one\nLine two",
        tags = listOf("brot", "sauerteig, mit komma"),
        durationSeconds = 754,
        channel = PlatformId.youtube("UCbbbbbbbbbbbbbbbbbbbbbb"),
        channelName = "Brotzeit",
        language = "de",
        thumbnailUrl = "https://example.invalid/$id.jpg",
        uploadedAt = Instant.parse("2025-03-01T10:00:00Z"),
        isShort = false,
        extra = mapOf("category" to "Howto & Style"),
    ),
    related = related.map {
        VideoStub(PlatformId.youtube(it), "Related $it", null, "Some Channel", 60, isShort = it == "rel2")
    },
)
