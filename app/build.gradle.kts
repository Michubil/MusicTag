import org.gradle.api.JavaVersion
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.ksp)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "top.michubil.musictag"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "top.michubil.musictag"
        minSdk = 35
        targetSdk = 37
        versionCode = 22
        versionName = "1.1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs.getByName("debug") {
        // Use the existing Windows user's identity even when Java runs as a sandbox account.
        val existingKey = file(providers.environmentVariable("USERPROFILE").get() + "/.android/debug.keystore")
        require(existingKey.isFile) { "Existing Music Tag signing key is missing; restore it before building." }
        storeFile = existingKey
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
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
        disable += listOf("ChromeOsAbiSupport", "LockedOrientationActivity", "DiscouragedApi", "NewerVersionAvailable")
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
    providers.gradleProperty("musicTagMp3Sample").orNull?.let { path ->
        val sample = file(path)
        inputs.file(sample).withPropertyName("mp3ArtworkSample")
        systemProperty("musicTag.mp3Sample", sample.absolutePath)
    }
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
