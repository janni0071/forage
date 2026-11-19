plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core"))
    implementation(libs.onnxruntime)
    implementation(libs.serialization.json)
    testImplementation(libs.kotlin.test)
    // The reference tokenizer (Rust through JNI, desktop only), to check the Kotlin one against.
    testImplementation(libs.djl.tokenizers)
}

tasks.test {
    useJUnitPlatform()
    // The model test needs the ~470 MB model; it runs only when this points at a downloaded copy.
    System.getenv("FORAGE_MODEL_DIR")?.let { environment("FORAGE_MODEL_DIR", it) }
    // A file of titles, one per line, to compare the Kotlin tokenizer with the reference on.
    System.getenv("FORAGE_TITLES")?.let { environment("FORAGE_TITLES", it) }
}
