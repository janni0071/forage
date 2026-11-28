plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core"))
    implementation(libs.lingua)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
    // The detector's language models need more than Gradle's default 512 MB test heap.
    maxHeapSize = "2g"
}
