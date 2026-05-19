import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(FileInputStream(keystorePropertiesFile))
    }
}

fun releaseSigningProp(propertyKey: String, envKey: String): String? =
    keystoreProperties.getProperty(propertyKey)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }

/** Git repo root (android/ is the Gradle rootProject). */
val repoRoot: File = rootProject.rootDir.parentFile

fun runGit(vararg args: String): String? =
    try {
        val proc =
            ProcessBuilder(listOf("git") + args)
                .directory(repoRoot)
                .redirectErrorStream(true)
                .start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        if (proc.waitFor() != 0 || out.isBlank()) null else out
    } catch (_: Exception) {
        null
    }

fun normalizeTag(tag: String): String =
    tag.removePrefix("refs/tags/").removePrefix("v")

fun semverToVersionCode(versionName: String): Int {
    val core = versionName.substringBefore('-').substringBefore('+')
    val parts = core.split('.')
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    return major * 10000 + minor * 100 + patch
}

fun resolveVersionName(): String {
    (findProperty("versionName") as String?)?.takeIf { it.isNotBlank() }?.let { return it }
    System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() }?.let { return it }
    runGit("describe", "--tags", "--exact-match", "HEAD")?.let { return normalizeTag(it) }
    return "1.0.0"
}

fun resolveVersionCode(versionName: String): Int {
    (findProperty("versionCode")?.toString())?.toIntOrNull()?.let { return it }
    System.getenv("VERSION_CODE")?.toIntOrNull()?.let { return it }
    return semverToVersionCode(versionName)
}

val appVersionName: String = resolveVersionName()
val appVersionCode: Int = resolveVersionCode(appVersionName)

android {
    namespace = "app.zerorelay"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.zerorelay"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        create("release") {
            val storePath = releaseSigningProp("storeFile", "ANDROID_KEYSTORE_FILE")
            if (storePath != null) {
                storeFile = rootProject.file(storePath)
                storePassword = releaseSigningProp("storePassword", "ANDROID_KEYSTORE_PASSWORD")
                keyAlias = releaseSigningProp("keyAlias", "ANDROID_KEY_ALIAS")
                keyPassword = releaseSigningProp("keyPassword", "ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig = if (releaseSigning.storeFile?.exists() == true) {
                releaseSigning
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.okhttp)
    implementation(libs.security.crypto)
    implementation(libs.bouncycastle)
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)

    debugImplementation(libs.androidx.compose.ui.tooling.preview)
}
