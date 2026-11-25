plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The GitHub release's build number (the release workflow sets SF_BUILD_NUMBER to the commit count; the updater
// compares it with the latest release's tag build-<n>). Local builds have 0 and never ask GitHub.
val buildNumber = providers.environmentVariable("SF_BUILD_NUMBER").orNull?.toIntOrNull() ?: 0
val buildCommit = providers.environmentVariable("SF_COMMIT").orNull ?: ""
// Release signing: the keystore SF_KEYSTORE names (scripts\release-key.ps1 puts it in the repository's secrets);
// without it, the debug key.
val releaseKeystore = providers.environmentVariable("SF_KEYSTORE").orNull

android {
    namespace = "dev.skyform.weather"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "dev.skyform.weather"
        minSdk = 29
        targetSdk = 36
        versionCode = if (buildNumber > 0) buildNumber else 1
        versionName = "1.0.0"
        buildConfigField("int", "BUILD_NUMBER", "$buildNumber")
        buildConfigField("String", "COMMIT", "\"$buildCommit\"")
        buildConfigField("String", "REPO", "\"rockenrooster/skyform\"")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("SF_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("SF_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("SF_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
        // The test app built like a release (R8, not debuggable; the debug build's id and key, so it installs over it).
        // Compose is several times slower when debuggable: judge smoothness with this. scripts\build.ps1 -Config staging
        create("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-staging"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
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
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)   // Android's org.json is a stub in JVM tests
}
