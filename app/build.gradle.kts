import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.starstacker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.starstacker"
        minSdk = 30          // FR-3.1: getConcurrentCameraIds() + scoped storage behaviour
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-probe"

        // FR-3.1 / plan D-8. Also cuts the OpenCV payload when it arrives in Phase 3.
        ndk { abiFilters += "arm64-v8a" }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
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
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)

    // D-7 / T-5.1 — Phase 3 only, and warp/transform primitives only. The arm64-only abiFilter
    // above (D-8) is what keeps this to a single native library rather than four. Loaded lazily by
    // `stacking/Resample.kt`, so the capture path never pays for it.
    implementation(libs.opencv)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
