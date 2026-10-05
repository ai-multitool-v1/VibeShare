import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.setbd.vibeshare.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.setbd.vibeshare"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("ci") {
            // Signing material is provided via CI secrets (see RELEASE.md).
            // Locally, gradle falls back to the debug keystore so builds never fail.
            val ksBase64 = System.getenv("VIBESHARE_KEYSTORE_BASE64") ?: ""
            if (ksBase64.isNotBlank()) {
                val ksFile = File(rootDir, "build/vibeshare-release.keystore").let { f ->
                    f.parentFile.mkdirs()
                    Base64.getDecoder().decode(ksBase64).inputStream().use { input ->
                        f.outputStream().use { input.copyTo(it) }
                    }
                    f
                }
                storeFile = ksFile
                storePassword = System.getenv("VIBESHARE_KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("VIBESHARE_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("VIBESHARE_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("VIBESHARE_KEYSTORE_BASE64")?.isNotBlank() == true) {
                signingConfig = signingConfigs.getByName("ci")
            } else {
                // Fallback so unsigned-capable CI builds still produce an installable APK.
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                test.maxHeapSize = "1200m"
            }
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":transfer"))
    implementation(project(":discovery"))
    implementation(project(":pairing"))
    implementation(project(":storage"))
    implementation(project(":apps"))
    implementation(project(":ui"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.androidx.work.runtime)

    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.compose.ui.test.junit4)
}
