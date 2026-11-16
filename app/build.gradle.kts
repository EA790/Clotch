import java.io.File

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.plugin.compose")
}

val versionPropsFile = layout.projectDirectory.file("version.properties")
val versionPropsText = providers.fileContents(versionPropsFile).asText.orNull
val baseVersionCode = versionPropsText
  ?.lineSequence()
  ?.firstOrNull { it.startsWith("VERSION_CODE=") }
  ?.substringAfter("=")
  ?.trim()
  ?.toIntOrNull() ?: 9

val isDebugBuild = gradle.startParameter.taskNames.any {
  it.contains("assembleDebug", ignoreCase = true) ||
    it.contains("installDebug", ignoreCase = true) ||
    it.equals("assemble", ignoreCase = true)
}
val computedVersionCode = if (isDebugBuild) baseVersionCode + 1 else baseVersionCode
val versionPropsPath = versionPropsFile.asFile.absolutePath

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.clotch.xkmqvr"
    minSdk = 24
    targetSdk = 36
    versionCode = computedVersionCode
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Leaving empty to use default Android debug keystore
    }
  }
  
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

dependencies {
  implementation(platform("androidx.compose:compose-bom:2024.09.00"))
  implementation("androidx.activity:activity-compose:1.10.1")
  implementation("androidx.compose.material:material-icons-core")
  implementation("androidx.compose.material:material-icons-extended")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-graphics")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.core:core-ktx:1.18.0")
  implementation("androidx.datastore:datastore-preferences:1.1.7")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
  implementation("androidx.navigation:navigation-compose:2.8.9")
  implementation("io.coil-kt:coil-compose:2.7.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
  implementation("com.squareup.okhttp3:okhttp:4.10.0")
  implementation("com.github.dead8309:KizzyRPC:1.0.71")
  implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7")
  implementation("androidx.media3:media3-exoplayer:1.6.1")
  implementation("androidx.media3:media3-transformer:1.6.1")
  implementation("androidx.media3:media3-effect:1.6.1")
  implementation("androidx.media3:media3-common:1.6.1")
  implementation("androidx.media3:media3-ui:1.6.1")
  implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
  implementation("com.google.mlkit:object-detection:17.0.2")

  testImplementation(platform("androidx.compose:compose-bom:2024.09.00"))
  testImplementation("androidx.compose.ui:ui-test-junit4")
  testImplementation("androidx.test:core:1.6.1")
  testImplementation("androidx.test.ext:junit:1.3.0")
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
  testImplementation("org.robolectric:robolectric:4.16.1")

  androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.00"))
  androidTestImplementation("androidx.compose.ui:ui-test-junit4")
  androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
  androidTestImplementation("androidx.test.ext:junit:1.3.0")
  androidTestImplementation("androidx.test:runner:1.6.2")

  debugImplementation("androidx.compose.ui:ui-test-manifest")
  debugImplementation("androidx.compose.ui:ui-tooling")
}

abstract class SaveVersionCodeTask : DefaultTask() {
  @get:Input
  abstract val versionCodeToSave: Property<Int>

  @get:Input
  abstract val targetFilePath: Property<String>

  @TaskAction
  fun save() {
    File(targetFilePath.get()).writeText("VERSION_CODE=${versionCodeToSave.get()}\n")
  }
}

val saveDebugVersionCode = tasks.register<SaveVersionCodeTask>("saveDebugVersionCode") {
  versionCodeToSave.set(computedVersionCode)
  targetFilePath.set(versionPropsPath)
  enabled = isDebugBuild
}

if (isDebugBuild) {
  tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy(saveDebugVersionCode)
  }
}
