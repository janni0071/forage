plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core"))
    implementation(libs.serialization.json)
    // The catalogue index is a plain SQLite file of its own.
    implementation(libs.sqlite.jdbc)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
}
