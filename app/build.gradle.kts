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
        // Stamped by tools/deploy.sh as yyDDDHHmm - two-digit year, day of year, hour, minute.
        //
        // It has to be an ascending Int for Android to consider one build newer than another, and
        // yyyyMMddHHmm overflows Int. This form stays under the limit until 2100 and is still
        // legible at a glance. Falls back to 1 for a plain `./gradlew assembleDebug`, which is
        // then older than anything published - so a hand-built apk never claims to be an update.
        versionCode = System.getenv("YTV_VERSION")?.toIntOrNull() ?: 1
        versionName = System.getenv("YTV_VERSION") ?: "dev"

        // The TCL reports abilist "armeabi-v7a,armeabi" - it is a 32-bit userspace despite the
        // 4K panel. mpv ships three ABIs at ~30MB each; carrying the two this device can never
        // load would triple the apk for nothing.
        ndk { abiFilters += listOf("armeabi-v7a") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor uses java.nio.file, which does not exist below API 33. minSdk here is
        // 30, so without this the app compiles and then dies with NoClassDefFoundError the first
        // time anything is resolved - on the television, not on this desk.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        // The update check compares its own versionCode against the published one.
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
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

    // libmpv, as an experiment against ExoPlayer's frame pacing. mpv is a genuinely different
    // timing architecture, and the judder on this panel has survived every ExoPlayer-side change.
    // 30MB of native code for one ABI, so the abiFilter below matters.
    implementation("io.github.wohal:mpv-android-lib:0.2.4")

    // Resolves a YouTube id to playable stream urls on the device. This replaced a server
    // endpoint running yt-dlp, and with it the last reason the app needed a machine at home:
    // signed urls die every few hours, so resolving cannot be done ahead of time and cached
    // into the published lineup. Rhino travels inside the library, so nothing else is needed
    // to run YouTube's signature javascript.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")

    testImplementation("junit:junit:4.13.2")

    // Backfills java.nio.file and the java.time pieces NewPipeExtractor expects, for the API
    // levels below 33 that this app still supports.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
