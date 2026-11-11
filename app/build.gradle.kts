import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "fr.pelis.iptv"
  compileSdk = 36
  defaultConfig {
    applicationId = "fr.pelis.iptv"
    minSdk = 24
    targetSdk = 36
    versionCode = 19
    // -PversionNameOverride=… : uniquement pour tester la mise à jour automatique.
    versionName = (project.findProperty("versionNameOverride") as String?) ?: "1.4.11"
  }

  // Clé de signature des releases : décrite dans keystore.properties (non versionné).
  // Sans ce fichier (ex. CI), l'APK release est produit non signé : jamais signé par erreur
  // avec une clé de debug différente de celle des versions publiées.
  val keystoreFile = rootProject.file("keystore.properties")
  val releaseSigning = if (keystoreFile.exists()) {
    val props = Properties().apply { keystoreFile.inputStream().use { load(it) } }
    signingConfigs.create("release") {
      storeFile = file(props.getProperty("storeFile"))
      storePassword = props.getProperty("storePassword")
      keyAlias = props.getProperty("keyAlias")
      keyPassword = props.getProperty("keyPassword")
    }
  } else {
    null
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      signingConfig = releaseSigning
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  testOptions {
    unitTests.isReturnDefaultValues = true
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = false
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.serialization.json)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  debugImplementation(libs.androidx.compose.ui.tooling)

  // Media3 / ExoPlayer for IPTV (HLS, MPEG-TS, MP4, MKV, DASH)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.exoplayer.hls)
  implementation(libs.androidx.media3.exoplayer.dash)
  implementation(libs.androidx.media3.ui)
  implementation(libs.androidx.media3.common)
  implementation(libs.androidx.media3.cast) // Chromecast (Google Cast)

  // Networking & Image loading
  implementation(libs.okhttp)
  implementation(libs.coil.compose)
  implementation(libs.zxing.core) // QR code (configuration depuis un téléphone)

  // Tests
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)
}
