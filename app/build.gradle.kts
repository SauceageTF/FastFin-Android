import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // Screenshot tests rendered on the JVM: interface changes get visual evidence
    // at phone and tablet sizes without a device.
    alias(libs.plugins.paparazzi)
}

android {
    namespace = "com.veeha.fastfin"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.veeha.fastfin"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.3.0"
    }

    buildTypes {
        release {
            // R8 full mode + resource shrinking: binary size is a product feature.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so the release APK can be sideloaded
            // directly. Swap in a real keystore before publishing anywhere.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version", "DebugProbesKt.bin")
    }

    lint {
        // Media3 marks most of its tuning surface (decoder fallback, OkHttp data
        // source, audio capabilities, subtitle view) @UnstableApi. We use it on
        // purpose and pin the version; each file opts in explicitly.
        disable += "UnsafeOptInUsageError"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // Installs the baseline profiles Compose and Media3 ship, so a sideloaded
    // APK starts as fast as a Play Store install.
    implementation(libs.androidx.profileinstaller)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.session)
    // Only for SubtitleView (renders PGS/VobSub bitmaps and styled ASS text).
    implementation(libs.media3.ui)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.coil.test)
}
