plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Sürüm numarası kök klasördeki version.txt'den okunur (ör. 0.10, 0.11, 1.0, 1.1)
val appVersion = rootProject.file("version.txt").readText().trim()
val appVersionCode = appVersion.split(".").let { p -> p[0].toInt() * 1000 + p.getOrElse(1) { "0" }.toInt() }

android {
    namespace = "com.dogukan.toonceviri"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dogukan.toonceviri"
        minSdk = 24
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersion
        // Yalnızca telefon işlemcileri (x86 emülatör kütüphaneleri APK'ya girmesin)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // İmza anahtarı GitHub Secrets'tan gelir (repoda durmaz).
    // Her derlemede aynı imza → yeni sürüm eskisinin üstüne güncelleme olarak kurulur.
    val keystorePath = System.getenv("SIGNING_KEYSTORE")
    val keystorePass = System.getenv("SIGNING_PASSWORD")?.trim()
    val hasKey = !keystorePath.isNullOrBlank() && !keystorePass.isNullOrBlank()

    signingConfigs {
        if (hasKey) {
            create("app") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePass
                keyAlias = "toonceviri"
                keyPassword = keystorePass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (hasKey) "app" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.1")

    // OCR: Latin (Türkçe, İngilizce vb.) uygulamanın içinde gelir
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Korece / Japonca / Çince OCR: Google Play Hizmetleri üzerinden, kullanıcı isterse iner
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-korean:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-japanese:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.1")
    implementation("com.google.android.gms:play-services-base:18.5.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // Çeviri (dil modeli bir kez iner, sonra internetsiz)
    implementation("com.google.mlkit:translate:17.0.3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
}
