import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.opengamepad"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.opengamepad"
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.0"
    }

    // Release signing: keystore.properties (local, never committed) or environment variables (CI).
    val keystoreFile = rootProject.file("keystore.properties")
    val keystore = Properties().apply { if (keystoreFile.exists()) keystoreFile.inputStream().use(::load) }
    fun secret(key: String, env: String): String? = keystore.getProperty(key) ?: System.getenv(env)
    val releaseStore = secret("storeFile", "OPENGAMEPAD_KEYSTORE")

    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = rootProject.file(releaseStore)
                storePassword = secret("storePassword", "OPENGAMEPAD_KEYSTORE_PASSWORD")
                keyAlias = secret("keyAlias", "OPENGAMEPAD_KEY_ALIAS")
                keyPassword = secret("keyPassword", "OPENGAMEPAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without a release key the APK is signed with the debug key: fine locally, not for publishing.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Screen-off with elevated permissions (ADB/Shizuku)
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")

    // QR scanner (fully open source, no Google Play Services)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.json:json:20240303")
}
