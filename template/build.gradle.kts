plugins {
    id("com.android.application")
}

android {
    namespace = "com.bookshell.shell"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bookshell.template"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.0-template"
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

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// No third-party libraries on purpose: the builder patches this APK's binary manifest, so the
// template must not pull in extra manifest entries (providers, receivers, permissions).
