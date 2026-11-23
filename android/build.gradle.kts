plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "forage.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.forage"
        // The shared code is built for Java 21; Android has all of its library methods from Android 15 (API 35) on.
        minSdk = 35
        targetSdk = 36
        versionCode = 28
        versionName = "0.9.1"
        // Phones of the last decade; leaving out the other processor types saves over 100 MB of ONNX Runtime.
        ndk { abiFilters += "arm64-v8a" }
    }

    // Release builds are signed with the key the user keeps: point FORAGE_KEYSTORE at it and give its
    // password in FORAGE_KEYSTORE_PASSWORD. Without them, release builds use the debug key.
    val keystore = System.getenv("FORAGE_KEYSTORE")?.let(::file)?.takeIf { it.isFile }
    signingConfigs {
        if (keystore != null) {
            create("personal") {
                storeFile = keystore
                storePassword = System.getenv("FORAGE_KEYSTORE_PASSWORD")
                keyAlias = "forage"
                keyPassword = System.getenv("FORAGE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Strips unused code only (see proguard-rules.pro): mostly the Material icons.
            isMinifyEnabled = true
            proguardFiles("proguard-rules.pro")
            signingConfig = if (keystore != null) signingConfigs.getByName("personal") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        // The shared modules are built for Java 21.
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures { compose = true }

    sourceSets["main"].java.srcDir("../app/src/main/kotlin")

    packaging {
        resources {
            excludes += setOf("META-INF/*.md", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/versions/**", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST")
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":source-youtube"))
    implementation(project(":source-pornhub"))
    implementation(project(":embedding-onnx"))
    implementation(libs.onnxruntime.android)
    implementation(libs.sqldelight.android.driver)
    implementation(libs.sqlcipher.android)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.androidx.webkit)
}

configurations.configureEach {
    // The desktop's JDBC driver has no Android build; the catalogue uses Android's own SQLite here.
    exclude(group = "org.xerial", module = "sqlite-jdbc")
}
