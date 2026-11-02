plugins {
  id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose")
}
android {
  namespace = "dev.apkforge"; compileSdk = 35; ndkVersion = "27.2.12479018"
  defaultConfig {
    applicationId = "dev.apkforge"; minSdk = 26; targetSdk = 28 // targetSdk 28 lets proot exec from app data
    versionCode = 1; versionName = "0.1"
    ndk { abiFilters += "arm64-v8a" }
    externalNativeBuild { cmake { arguments += listOf("-DGGML_OPENMP=OFF", "-DLLAMA_CURL=OFF") } }
  }
  externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
  buildFeatures { compose = true }
  packaging { jniLibs.useLegacyPackaging = true; resources.excludes += "META-INF/**" }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
}
dependencies {
  implementation(platform("androidx.compose:compose-bom:2024.10.01"))
  implementation("androidx.compose.material3:material3")
  implementation("androidx.activity:activity-compose:1.9.3")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("org.json:json:20240303")
  implementation("androidx.core:core-ktx:1.13.1")
  // pure-JVM tools: work with zero setup
  implementation("com.android.tools.smali:smali:3.0.8")
  implementation("com.android.tools.smali:smali-baksmali:3.0.8")
  implementation("com.android.tools.build:apksig:8.7.2")
  implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
}
