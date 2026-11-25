plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Build time in minutes since epoch: always increases, so both local and CI
// builds can be installed on top of each other.
val buildMinutes = (System.currentTimeMillis() / 60000L).toInt()

// Release signing comes from the environment (GitHub Secrets). Never commit keys.
val ksFile = System.getenv("KEYSTORE_FILE")
val ksPassword = System.getenv("KEYSTORE_PASSWORD")
val ksAlias = System.getenv("KEY_ALIAS")
val ksKeyPassword = System.getenv("KEY_PASSWORD")
val hasSigning = !ksFile.isNullOrBlank() && !ksPassword.isNullOrBlank() && !ksAlias.isNullOrBlank()

android {
    namespace = "com.phonecleaner.ai"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.phonecleaner.ai"
        minSdk = 30
        targetSdk = 37
        versionCode = buildMinutes
        versionName = "1.0.$buildMinutes"
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(ksFile!!)
                storePassword = ksPassword
                keyAlias = ksAlias
                keyPassword = ksKeyPassword ?: ksPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        generateLocaleConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.11.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    implementation("com.squareup.okhttp3:okhttp:5.5.1")

    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
}
