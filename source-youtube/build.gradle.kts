plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core"))
    implementation(libs.newpipe.extractor)
    implementation(libs.jsoup)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
}
