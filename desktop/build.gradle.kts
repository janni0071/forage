import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":source-youtube"))
    implementation(project(":embedding-onnx"))
    implementation(project(":language-lingua"))
    implementation(libs.sqldelight.sqlite.driver)
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
        // The language detector's models need more than the JVM's default heap.
        jvmArgs += listOf("-Xmx2g")
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "forage"
            packageVersion = "0.1.0"
            description = "Local-first video recommendations you control"
            licenseFile.set(rootProject.file("LICENSE"))
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
