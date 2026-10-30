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
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            val path = System.getenv("MDOCK_KEYSTORE")
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("MDOCK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MDOCK_KEY_ALIAS")
                keyPassword = System.getenv("MDOCK_KEY_PASSWORD")
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
