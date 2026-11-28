plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}

val forageVersion = providers.gradleProperty("forage.version").get()

subprojects {
    group = "forage"
    version = forageVersion
}

// The version as a constant both apps compile (they share the app sources), so each knows what it
// is: for the update check, and to say so in Settings. Written only when it changes.
val versionSource = "package forage.app\n\n/** This build's version, from gradle.properties (forage.version). */\n" +
    "const val FORAGE_VERSION = \"$forageVersion\"\n"
layout.buildDirectory.file("generated/version/forage/app/ForageVersion.kt").get().asFile.let { file ->
    if (!file.isFile || file.readText() != versionSource) {
        file.parentFile.mkdirs()
        file.writeText(versionSource)
    }
}
