import java.io.File
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.google.services)
  alias(libs.plugins.roborazzi)
}

fun String.asBuildConfigString(): String =
  "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

val localProperties = Properties().apply {
  val localPropertiesFile = rootProject.file("local.properties")
  if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { load(it) }
  }
}

fun buildWeekConfig(name: String, defaultValue: String): String =
  providers.gradleProperty(name).orNull ?: localProperties.getProperty(name) ?: defaultValue

fun externalDebugConfig(name: String, defaultValue: String): String =
  providers.gradleProperty(name).orNull
    ?: providers.environmentVariable(name).orNull
    ?: localProperties.getProperty(name)
    ?: defaultValue

val ciSigningEnabled = System.getenv("FITDESI_CI_SIGNING_ENABLED") == "true"

fun requiredCiSigningValue(name: String): String =
  System.getenv(name)?.takeIf(String::isNotBlank)
    ?: throw GradleException("$name is required when FITDESI_CI_SIGNING_ENABLED=true.")

data class ReleaseSigningValues(
  val keystoreFile: File,
  val keystorePassword: String,
  val keyAlias: String,
  val keyPassword: String
)

fun optionalReleaseSigningValue(name: String): String? =
  System.getenv(name)?.takeIf(String::isNotBlank)

fun completeReleaseSigningValues(): ReleaseSigningValues? {
  val keystorePath = optionalReleaseSigningValue("FITDESI_RELEASE_KEYSTORE_PATH") ?: return null
  val keystorePassword = optionalReleaseSigningValue("FITDESI_RELEASE_KEYSTORE_PASSWORD") ?: return null
  val keyAlias = optionalReleaseSigningValue("FITDESI_RELEASE_KEY_ALIAS") ?: return null
  val keyPassword = optionalReleaseSigningValue("FITDESI_RELEASE_KEY_PASSWORD") ?: return null

  return ReleaseSigningValues(
    keystoreFile = file(keystorePath).canonicalFile,
    keystorePassword = keystorePassword,
    keyAlias = keyAlias,
    keyPassword = keyPassword
  )
}

fun requiredReleaseSigningValues(): ReleaseSigningValues {
  val keystorePath = optionalReleaseSigningValue("FITDESI_RELEASE_KEYSTORE_PATH")
    ?: throw GradleException("FITDESI_RELEASE_KEYSTORE_PATH is required for a signed release build.")
  val keystorePassword = optionalReleaseSigningValue("FITDESI_RELEASE_KEYSTORE_PASSWORD")
    ?: throw GradleException("FITDESI_RELEASE_KEYSTORE_PASSWORD is required for a signed release build.")
  val keyAlias = optionalReleaseSigningValue("FITDESI_RELEASE_KEY_ALIAS")
    ?: throw GradleException("FITDESI_RELEASE_KEY_ALIAS is required for a signed release build.")
  val keyPassword = optionalReleaseSigningValue("FITDESI_RELEASE_KEY_PASSWORD")
    ?: throw GradleException("FITDESI_RELEASE_KEY_PASSWORD is required for a signed release build.")
  val keystoreFile = file(keystorePath).canonicalFile

  if (!keystoreFile.isFile) {
    throw GradleException(
      "FITDESI_RELEASE_KEYSTORE_PATH must point to an existing regular keystore file for a signed release build."
    )
  }

  return ReleaseSigningValues(keystoreFile, keystorePassword, keyAlias, keyPassword)
}

fun isSigningDependentReleaseTask(taskName: String): Boolean {
  val simpleName = taskName.substringAfterLast(':').lowercase()
  if (simpleName in setOf("assemble", "build", "bundle", "package", "publish")) {
    return true
  }
  if (!simpleName.contains("release")) {
    return false
  }
  return listOf(
    "assemble",
    "bundle",
    "install",
    "package",
    "publish",
    "sign",
    "upload",
    "validatesigning"
  ).any(simpleName::startsWith)
}

val releaseSigningRequested =
  gradle.startParameter.taskNames.any(::isSigningDependentReleaseTask)
val releaseSigningValues =
  if (releaseSigningRequested) requiredReleaseSigningValues() else completeReleaseSigningValues()
val applicationProjectPath = project.path
val revenueCatDebugRequested =
  externalDebugConfig("FITDESI_REVENUECAT_ENABLED", "false").toBoolean()
val revenueCatDebugKeyCandidate =
  externalDebugConfig("FITDESI_REVENUECAT_PUBLIC_KEY", "").trim()
val revenueCatDebugPublicKey = revenueCatDebugKeyCandidate.takeIf { candidate ->
  revenueCatDebugRequested &&
    candidate.isNotBlank() &&
    !candidate.startsWith("sk_", ignoreCase = true)
}.orEmpty()
val revenueCatDebugEnabled = revenueCatDebugRequested && revenueCatDebugPublicKey.isNotEmpty()

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.fitdesiai.app"
    minSdk = 24
    targetSdk = 36
    versionCode = 14
    versionName = "0.2.0-alpha"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    val safeMode = buildWeekConfig("FITDESI_BUILD_WEEK_SAFE_MODE", "true").toBoolean()
    buildConfigField("boolean", "BUILD_WEEK_SAFE_MODE", safeMode.toString())
    buildConfigField("boolean", "AI_PROXY_ENABLED", buildWeekConfig("FITDESI_AI_PROXY_ENABLED", "false").toBoolean().toString())
    buildConfigField("boolean", "EXERCISE_PROXY_ENABLED", buildWeekConfig("FITDESI_EXERCISE_PROXY_ENABLED", "false").toBoolean().toString())
    buildConfigField("boolean", "FIRESTORE_SYNC_ENABLED", buildWeekConfig("FITDESI_FIRESTORE_SYNC_ENABLED", "false").toBoolean().toString())
    buildConfigField("boolean", "ALLOW_DESTRUCTIVE_MIGRATION", buildWeekConfig("FITDESI_ALLOW_DESTRUCTIVE_MIGRATION", "false").toBoolean().toString())
    buildConfigField(
      "String",
      "AI_RUNTIME_MODE",
      buildWeekConfig("FITDESI_AI_RUNTIME_MODE", "LOCAL").uppercase().asBuildConfigString()
    )
  }

  signingConfigs {
    if (ciSigningEnabled) {
      create("ciDebug") {
        storeFile = file(requiredCiSigningValue("FITDESI_CI_KEYSTORE_PATH"))
        storePassword = requiredCiSigningValue("FITDESI_KEYSTORE_PASSWORD")
        keyAlias = requiredCiSigningValue("FITDESI_KEY_ALIAS")
        keyPassword = requiredCiSigningValue("FITDESI_KEY_PASSWORD")
      }
    }
    create("release") {
      releaseSigningValues?.let { values ->
        storeFile = values.keystoreFile
        storePassword = values.keystorePassword
        keyAlias = values.keyAlias
        keyPassword = values.keyPassword
      }
    }
  }

  buildTypes {
    release {
      buildConfigField("boolean", "FITDESI_REVENUECAT_ENABLED", "false")
      buildConfigField("String", "FITDESI_REVENUECAT_PUBLIC_KEY", "".asBuildConfigString())
      buildConfigField(
        "String",
        "FITDESI_BACKEND_BASE_URL",
        buildWeekConfig("FITDESI_BACKEND_RELEASE_BASE_URL", "https://example.invalid/").asBuildConfigString()
      )
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      applicationIdSuffix = ".qa"
      buildConfigField("boolean", "FITDESI_BOOST_ADS_ENABLED", "true")
      buildConfigField(
        "String",
        "FITDESI_BOOST_REWARDED_AD_UNIT_ID",
        "ca-app-pub-8680899302459363/7460434993".asBuildConfigString()
      )
      buildConfigField(
        "boolean",
        "FITDESI_REVENUECAT_ENABLED",
        revenueCatDebugEnabled.toString()
      )
      buildConfigField(
        "String",
        "FITDESI_REVENUECAT_PUBLIC_KEY",
        revenueCatDebugPublicKey.asBuildConfigString()
      )
      if (ciSigningEnabled) {
        signingConfig = signingConfigs.getByName("ciDebug")
      }
      buildConfigField(
        "String",
        "FITDESI_BACKEND_BASE_URL",
        buildWeekConfig("FITDESI_BACKEND_DEBUG_BASE_URL", "http://10.0.2.2:3000/").asBuildConfigString()
      )
    }
  }
  gradle.taskGraph.whenReady {
    val releaseSigningTaskPresent = allTasks.any { task ->
      task.project.path == applicationProjectPath && isSigningDependentReleaseTask(task.name)
    }
    if (releaseSigningTaskPresent) {
      val values = requiredReleaseSigningValues()
      signingConfigs.getByName("release").apply {
        storeFile = values.keystoreFile
        storePassword = values.keystorePassword
        keyAlias = values.keyAlias
        keyPassword = values.keyPassword
      }
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
  sourceSets.getByName("debug").assets.srcDir("$projectDir/schemas")
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.auth)
  implementation(libs.gson)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  implementation(libs.revenuecat.purchases)
  implementation(libs.material)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.mockwebserver)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.room.testing)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  debugImplementation(libs.revenuecat.purchases.admob)
  debugImplementation(libs.google.ump)
  debugImplementation(libs.google.mobile.ads)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

tasks.register("copyApkToRoot") {
  dependsOn("assembleDebug")
  doLast {
    val apkFile = file("${layout.buildDirectory.get().asFile}/outputs/apk/debug/app-debug.apk")
    val destFile = file("${rootDir}/app-debug.apk")
    if (apkFile.exists()) {
      apkFile.copyTo(destFile, overwrite = true)
      println("Successfully copied APK to root folder: ${destFile.absolutePath}")
    } else {
      throw GradleException("Source APK not found at: ${apkFile.absolutePath}")
    }
  }
}
