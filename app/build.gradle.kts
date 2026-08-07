plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.cliftonia.fs42tv"
    // 36 because media3 1.10.1 requires it - checkDebugAarMetadata refuses to build against 35.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cliftonia.fs42tv"
        minSdk = 30
        // Deliberately still 35 while compileSdk is 36. Raising compileSdk only changes what
        // the app can be compiled against; raising targetSdk opts into new platform behaviour
        // at runtime, which is exactly the kind of observable change this task exists not to
        // make. It is a separate decision, on its own evidence.
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    // 1.3.1 shipped PreloadMediaSource but not DefaultPreloadManager, which arrived in 1.4.0.
    // 1.10.1 also brings TargetPreloadStatusControl and RankingDataComparator - the pair that
    // lets the channel behind outrank a nearer channel ahead, which a plain distance metric
    // ties. Verified by unpacking the AAR rather than assumed.
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")

    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    // Pulls androidx.activity itself at the same version, so the separate pin that used to sit
    // beside media3 is gone rather than left to drift a minor behind this one.
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.tv:tv-material:1.1.0")

    testImplementation("junit:junit:4.13.2")
}
