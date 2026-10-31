plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "zai.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "zai.mobile"
        minSdk = 24
        targetSdk = 34
        // CI: -PzaiVersionCode=N -PzaiVersionName=... keçir; lokal default 7001
        versionCode = (project.findProperty("zaiVersionCode") as String?)?.toInt() ?: 7001
        versionName = (project.findProperty("zaiVersionName") as String?) ?: "7.0.0-beta.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            // İmzalama CI-də apksigner ilə aparılır (köhnə axın ilə eyni)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Yeganə xarici asılılıq — korutinlər (Dərs 15-16)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
