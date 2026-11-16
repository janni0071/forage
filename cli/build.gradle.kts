plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":source-youtube"))
    implementation(libs.sqldelight.sqlite.driver)
}

application {
    mainClass = "forage.cli.MainKt"
    applicationName = "forage"
}

// `gradle :cli:run --args="..."` should resolve paths relative to where it was invoked.
tasks.named<JavaExec>("run") {
    workingDir = File(System.getProperty("user.dir"))
}
