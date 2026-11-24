plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Car screen mockups for the README (tools/mockups.sh): Paparazzi draws them on the JVM. Only with
// -Pmockups, so ordinary builds and CI don't carry it; it doesn't support the configuration cache here.
val mockups = providers.gradleProperty("mockups").isPresent
if (mockups) apply(plugin = libs.plugins.paparazzi.get().pluginId)

android {
    namespace = "net.prototux.aare"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "net.prototux.aare"
        // Android 6. Newer features need newer Android: notification channels and foreground-
        // service start (8), car audio by screen capture and the modern Wi-Fi join (10), Shizuku
        // capture (12); older phones fall back or go without. A one-time notice on Android 6 and 7
        // (MainActivity) lists what's limited there.
        minSdk = 23
        targetSdk = 37
        // The released version, raised in the release commit (the release workflow checks that the
        // tag matches): F-Droid builds the tag and reads it from here. versionCode is
        // major * 10000 + minor * 100 + patch. -PversionName / -PversionCode still override it.
        versionCode = 10600
        versionName = "1.6.0"
        providers.gradleProperty("versionCode").orNull?.let { versionCode = it.toInt() }
        providers.gradleProperty("versionName").orNull?.let { versionName = it }
        // The check for a newer release on GitHub (UpdateChecker): off with -Paare.updateCheck=false,
        // for stores that update the app themselves (F-Droid).
        buildConfigField("boolean", "UPDATE_CHECK", (providers.gradleProperty("aare.updateCheck").orNull != "false").toString())
    }

    signingConfigs {
        // Release key from the environment (the release workflow's secrets). Without it the
        // release APK is unsigned: use the debug one locally.
        val keystore = providers.environmentVariable("AARE_KEYSTORE").orNull
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = providers.environmentVariable("AARE_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("AARE_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("AARE_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            optimization {
                enable = false
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
        aidl = true
    }
    if (mockups) sourceSets["test"].kotlin.srcDir("src/mockups/java")
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**")
    }
    lint {
        // a sideloaded app: no Play Store policy checks
        disable += setOf("ProtectedPermissions", "QueryAllPackagesPermission")
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

dependencies {
    implementation(project(":aap"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.car.app)
    implementation(libs.androidx.car.app.projected)
    implementation(libs.media3.session)
    implementation(libs.kotlinx.coroutines.android)
    // Car audio without the screen-capture consent: through Shizuku (adb rights), or Android's
    // audio-policy interface when installed as a privileged app (tools/magisk).
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.hiddenapibypass)
    testImplementation(libs.junit)
    // vehicles.json is parsed with the platform's org.json, which unit tests only see as a stub.
    testImplementation(libs.org.json)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
