plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val releaseKeystoreFile: String? = System.getenv("ANDROID_KEYSTORE_FILE")
val releaseKeystorePassword: String? = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = System.getenv("ANDROID_KEY_ALIAS")
val releaseKeyPassword: String? = System.getenv("ANDROID_KEY_PASSWORD")
val hasReleaseSigning = !releaseKeystoreFile.isNullOrBlank() &&
    !releaseKeystorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.BalancedLight.dynamicdriving"
    compileSdk = 36

    // The AAOS build reads the vehicle's own speed through the platform car API.
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.BalancedLight.dynamicdriving"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0p"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystoreFile!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += "wav"
    }

    lint {
        abortOnError = true
        checkDependencies = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
