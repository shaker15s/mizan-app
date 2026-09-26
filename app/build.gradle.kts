plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

val apiBaseUrl = (System.getenv("WAKEEL_API_BASE_URL") ?: "")
    .replace("\\", "")
    .replace("\"", "")

/**
 * The authority's receipt key, pinned at build time.
 *
 * It is a build input and not a runtime fetch on purpose: a phone that
 * downloads the key it verifies signatures with is verifying them with
 * whatever the server sends, which is no verification at all.
 */
val receiptPublicKey = (System.getenv("WAKEEL_RECEIPT_PUBLIC_KEY") ?: "")
    .replace("\\", "")
    .replace("\"", "")
val receiptKeyId = (System.getenv("WAKEEL_RECEIPT_KEY_ID") ?: "")
    .replace("\\", "")
    .replace("\"", "")

android {
    namespace = "app.mizan"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "app.mizan"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "2.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    /**
     * The channel decides whether the device is allowed to simulate.
     *
     * demo        local simulator only, no service URL, results labeled simulation
     * staging     talks to the Wakeel service, refuses writes without an HTTPS URL
     * production  same as staging, no suffix, no simulator on the classpath
     *
     * The simulation classes live in `src/demo`, so a staging or production
     * build cannot contain them, and `createAuthority` is supplied per flavor.
     */
    flavorDimensions += "channel"

    productFlavors {
        create("demo") {
            dimension = "channel"
            applicationIdSuffix = ".demo"
            buildConfigField("boolean", "DEMO_MODE", "true")
            buildConfigField("String", "WAKEEL_ENV", "\"demo\"")
            buildConfigField("String", "API_BASE_URL", "\"\"")
            buildConfigField("String", "RECEIPT_PUBLIC_KEY", "\"\"")
            buildConfigField("String", "RECEIPT_KEY_ID", "\"\"")
        }
        create("staging") {
            dimension = "channel"
            applicationIdSuffix = ".staging"
            buildConfigField("boolean", "DEMO_MODE", "false")
            buildConfigField("String", "WAKEEL_ENV", "\"staging\"")
            buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
            // The receipt authority key this build pins. Empty means the build
            // has pinned nothing, and the app then says it cannot verify a
            // receipt rather than showing a tick it cannot justify.
            buildConfigField("String", "RECEIPT_PUBLIC_KEY", "\"$receiptPublicKey\"")
            buildConfigField("String", "RECEIPT_KEY_ID", "\"$receiptKeyId\"")
        }
        create("production") {
            dimension = "channel"
            buildConfigField("boolean", "DEMO_MODE", "false")
            buildConfigField("String", "WAKEEL_ENV", "\"production\"")
            buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
            buildConfigField("String", "RECEIPT_PUBLIC_KEY", "\"$receiptPublicKey\"")
            buildConfigField("String", "RECEIPT_KEY_ID", "\"$receiptKeyId\"")
        }
    }

    signingConfigs {
        create("release") {
            val path = System.getenv("KEYSTORE_PATH")
            if (!path.isNullOrBlank()) {
                storeFile = file(path)
            }
            storePassword = System.getenv("STORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS") ?: "upload"
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            val path = System.getenv("KEYSTORE_PATH")
            val ready = !path.isNullOrBlank() &&
                file(path).exists() &&
                !System.getenv("STORE_PASSWORD").isNullOrBlank() &&
                !System.getenv("KEY_PASSWORD").isNullOrBlank()
            if (ready) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
        )
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":integration"))
    implementation(project(":design"))
    implementation(libs.androidx.room.runtime)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.biometric)
    implementation(libs.okhttp)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
