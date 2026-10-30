plugins { alias(libs.plugins.android.application) }

android {
    namespace = "com.ahao.clicker.acceptance.target"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.ahao.clicker.acceptance.target"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            matchingFallbacks += "release"
        }
    }
}

dependencies {
    implementation(project(":core"))
    testImplementation(libs.junit)
}
