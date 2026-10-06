plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }

android {
    namespace = "com.cma.kreels"
    compileSdk = 35
    defaultConfig { applicationId = "com.cma.kreels"; minSdk = 29; targetSdk = 35; versionCode = 1; versionName = "0.1" }
    androidResources { noCompress += listOf("ktx", "glb") }   // keep big assets uncompressed
    signingConfigs {
        create("release") {                      // filled only when CI provides the keystore secrets
            System.getenv("KEYSTORE_PATH")?.let {
                storeFile = file(it); storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS"); keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false              // Filament uses JNI; keep it simple
            if (System.getenv("KEYSTORE_PATH") != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val filament = "1.56.0"   // bump to latest
    implementation("com.google.android.filament:filament-android:$filament")
    implementation("com.google.android.filament:gltfio-android:$filament")
    implementation("com.google.android.filament:filament-utils-android:$filament")

    val media3 = "1.5.1"      // bump to latest
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")

    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")   // bundled model, runs offline
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
