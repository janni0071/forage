plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core"))
    implementation(libs.onnxruntime)
    // Desktop-only for now: wraps the Rust tokenizers library through JNI. Android needs its own answer in M3.
    implementation(libs.djl.tokenizers)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
    // The model test needs the ~470 MB model; it runs only when this points at a downloaded copy.
    System.getenv("FORAGE_MODEL_DIR")?.let { environment("FORAGE_MODEL_DIR", it) }
}
