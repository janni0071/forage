import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(21)
    // The model and screens shared with the Android app.
    sourceSets.main { kotlin.srcDir("../app/src/main/kotlin") }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":source-youtube"))
    implementation(project(":source-pornhub"))
    implementation(project(":embedding-onnx"))
    implementation(libs.onnxruntime)
    implementation(project(":language-lingua"))
    implementation(libs.sqldelight.sqlite.driver)
    implementation(libs.sqlite.jdbc)
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    // Large, but packaging strips the icons that aren't used.
    implementation(libs.compose.icons)
    runtimeOnly(libs.slf4j.nop)
    testImplementation(libs.kotlin.test)
}

compose.desktop {
    application {
        mainClass = "forage.desktop.MainKt"
        // The language detector's models need more than the JVM's default heap, and so does importing a catalogue.
        jvmArgs += listOf("-Xmx3g")
        buildTypes.release.proguard {
            configurationFiles.from(project.file("rules.pro"))
            obfuscate.set(false)
            optimize.set(false)
        }
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Dmg, TargetFormat.Msi)
            // The name people see; Debian's package name below must be lowercase.
            packageName = "Forage"
            packageVersion = "0.7.0"
            description = "Local-first video recommendations you control"
            vendor = "Forage"
            licenseFile.set(rootProject.file("LICENSE"))
            // From suggestRuntimeModules, plus what HTTPS and the database driver need at run time.
            modules(
                "java.compiler", "java.instrument", "java.management", "java.net.http", "java.sql",
                "jdk.dynalink", "jdk.unsupported", "jdk.crypto.ec", "java.naming",
            )
            linux {
                packageName = "forage"
                iconFile.set(project.file("icons/forage.png"))
                shortcut = true
                menuGroup = "AudioVideo"
                appCategory = "AudioVideo"
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

// Renders the screens headlessly: gradle :desktop:screenshots -Pdata=DIR -Pout=DIR
tasks.register<JavaExec>("screenshots") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "forage.desktop.ScreenshotsKt"
    jvmArgs("-Djava.awt.headless=true", "-Xmx2g")
    args(
        (project.findProperty("data") ?: rootProject.file("data").path).toString(),
        (project.findProperty("out") ?: layout.buildDirectory.dir("screenshots").get().asFile.path).toString(),
    )
}

// The shared screens at a phone's size: gradle :desktop:phoneScreenshots -Pdata=DIR -Pout=DIR
tasks.register<JavaExec>("phoneScreenshots") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "forage.desktop.PhoneScreenshotsKt"
    jvmArgs("-Djava.awt.headless=true", "-Xmx2g")
    args(
        (project.findProperty("data") ?: rootProject.file("data").path).toString(),
        (project.findProperty("out") ?: layout.buildDirectory.dir("screenshots").get().asFile.path).toString(),
    )
}

// Packaging builds for the machine it runs on, so the packaged jars only need that platform's
// native libraries. Several libraries ship them for every platform; together with language models
// Forage never loads, that more than doubled the package. This trims the jars after ProGuard.
val hostNatives: Map<String, String>? = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").lowercase().let { it == "aarch64" || it == "arm64" }
    when {
        "linux" in os && !arm -> mapOf("onnx" to "linux-x64", "sqlite" to "Linux/x86_64")
        "linux" in os -> mapOf("onnx" to "linux-aarch64", "sqlite" to "Linux/aarch64")
        "mac" in os && arm -> mapOf("onnx" to "osx-aarch64", "sqlite" to "Mac/aarch64")
        "win" in os && !arm -> mapOf("onnx" to "win-x64", "sqlite" to "Windows/x86_64")
        else -> null // unknown platform: keep everything
    }
}

// Must match LinguaDetector.COMMON_LANGUAGES.
val linguaLanguages = setOf(
    "en", "de", "fr", "es", "pt", "it", "nl", "pl", "hr", "sv", "tr", "id", "vi",
    "ru", "uk", "el", "ar", "he", "hi", "th", "zh", "ja", "ko",
)

fun keepEntry(name: String): Boolean {
    fun onlyHost(prefix: String, key: String): Boolean =
        !name.startsWith(prefix) || hostNatives == null || name == prefix || name.startsWith(prefix + hostNatives.getValue(key) + "/") ||
            // Files beside the platform folders are needed everywhere.
            '/' !in name.removePrefix(prefix) ||
            // Directory entries on the way to the kept folder.
            (name.endsWith("/") && (prefix + hostNatives.getValue(key) + "/").startsWith(name))
    if (name.startsWith("language-models/")) {
        val code = name.removePrefix("language-models/").substringBefore('/')
        return code.isEmpty() || code in linguaLanguages
    }
    return onlyHost("ai/onnxruntime/native/", "onnx") && onlyHost("org/sqlite/native/", "sqlite") &&
        // Debug symbols for the macOS library alone are 58 MB.
        !name.contains(".dSYM/")
}

fun slimJar(jar: File) {
    val names = ZipFile(jar).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
    if (names.all(::keepEntry)) return
    val slim = File(jar.path + ".slim")
    ZipFile(jar).use { zip ->
        ZipOutputStream(slim.outputStream().buffered()).use { out ->
            for (entry in zip.entries()) {
                if (!keepEntry(entry.name)) continue
                out.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                zip.getInputStream(entry).use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }
    jar.delete()
    slim.renameTo(jar)
}

tasks.matching { it.name == "proguardReleaseJars" }.configureEach {
    doLast {
        outputs.files.asFileTree.matching { include("**/*.jar") }.forEach(::slimJar)
    }
}
