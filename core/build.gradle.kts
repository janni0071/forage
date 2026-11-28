plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvmToolchain(21)
}

sqldelight {
    databases {
        create("ForageDatabase") {
            packageName.set("forage.core.db")
        }
    }
}

dependencies {
    implementation(libs.serialization.json)
    implementation(libs.jsoup)
    testImplementation(libs.kotlin.test)
    // Core only sees the driver interface; each app supplies its platform's driver.
    testImplementation(libs.sqldelight.sqlite.driver)
}

tasks.test {
    useJUnitPlatform()
}
