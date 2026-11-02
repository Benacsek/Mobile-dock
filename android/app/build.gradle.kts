plugins {
    id("com.android.application")
}

android {
    namespace = "dev.mobiledock"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.mobiledock"
        // Android 4.1 Jelly Bean. The app uses no libraries, so it runs on very old phones.
        minSdk = 16
        targetSdk = 36
        // CI sets these from the run number. Each release must have a higher code to update phones.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toInt()?.plus(100) ?: 1
        versionName = System.getenv("MDOCK_VERSION_NAME") ?: "1.0.0-dev"
    }

    signingConfigs {
        create("release") {
            val path = System.getenv("MDOCK_KEYSTORE")
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("MDOCK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MDOCK_KEY_ALIAS") ?: "mobiledock"
                keyPassword = System.getenv("MDOCK_KEY_PASSWORD") ?: System.getenv("MDOCK_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("MDOCK_KEYSTORE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    lint {
        abortOnError = true
    }
}
