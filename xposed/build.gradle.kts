// A standalone libxposed module: a separate APK, installed and enabled in an Xposed manager
// (LSPosed/Vector) on a rooted phone. It is not part of the AARE app and ships nothing itself —
// see xposed/README.md and docs/xposed.md. Its hooks are compatibility shims for AARE only.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "net.prototux.aare.xposed"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "net.prototux.aare.xposed"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // The framework provides the API at runtime; compile against it, never bundle it.
    compileOnly(libs.libxposed.api)
}
