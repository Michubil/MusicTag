import org.gradle.api.JavaVersion
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.ksp)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
        .asText.orNull?.reader()?.use(::load)
}
val acoustIdClientKey = providers.environmentVariable("ACOUSTID_CLIENT_KEY").orNull
    ?.takeIf(String::isNotBlank) ?: localProperties.getProperty("acoustid.clientKey").orEmpty()
require(acoustIdClientKey.matches(Regex("[A-Za-z0-9]*"))) { "ACOUSTID_CLIENT_KEY must be alphanumeric" }

android {
    namespace = "top.michubil.musictag"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "top.michubil.musictag"
        minSdk = 35
        targetSdk = 37
        versionCode = 33
        versionName = "1.2.8"

        ndk {
            abiFilters += "arm64-v8a"
        }
        buildConfigField("String", "ACOUSTID_CLIENT_KEY", "\"$acoustIdClientKey\"")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs.create("release") {
        // PR checks need no release key; validateSigningRelease checks it when packaging.
        storeFile = file(providers.environmentVariable("MUSICTAG_KEYSTORE_PATH")
            .orElse(providers.environmentVariable("USERPROFILE").map { "$it/.android/debug.keystore" }).get())
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = true
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        localeFilters += listOf("en", "zh-rCN")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    packaging {
        dex {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/DebugProbesKt.bin"
        }
    }

    lint {
        // Dependency upgrades are deliberate; publishing a newer version must not break this build.
        disable += listOf("ChromeOsAbiSupport", "LockedOrientationActivity", "DiscouragedApi",
            "GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
        warningsAsErrors = true
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set(
                output.versionName.map { versionName -> "MusicTag-v$versionName.apk" },
            )
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
        allWarningsAsErrors = true
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Keep disposable audio fixtures inside this checkout, including under Windows sandboxing.
    systemProperty("java.io.tmpdir", temporaryDir.absolutePath)
}

dependencies {
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit.vintage)
}
