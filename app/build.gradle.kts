plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.ahao.clicker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ahao.clicker"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "com.ahao.clicker.acceptance.MvpAcceptance"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        viewBinding = true
    }

    sourceSets.getByName("androidTest").assets.directories.add(
        layout.buildDirectory.dir("generated/acceptance-assets").get().asFile.path,
    )

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        // Pinned SDK/toolchain upgrades are reviewed separately; keep upgrade notices visible.
        informational += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "OldTargetApi")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
