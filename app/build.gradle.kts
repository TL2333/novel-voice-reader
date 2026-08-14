import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.gradle.api.tasks.Sync

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.tl2333.novelvoicereader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tl2333.novelvoicereader"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk.abiFilters += "arm64-v8a"

        buildConfigField("String", "READIUM_VERSION", "\"3.2.0\"")
        buildConfigField("String", "SHERPA_ONNX_VERSION", "\"1.13.4\"")
        buildConfigField("String", "KOKORO_COMMIT", "\"155831f1b4ba23b1f5c058be6a61df90cefb2a37\"")
    }

    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    sourceSets {
        getByName("main").assets.srcDir("build/generated/offlineAssets")
    }

    androidResources {
        noCompress += listOf("onnx", "bin", "fst", "wav")
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
        jniLibs.useLegacyPackaging = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        languageVersion = KotlinVersion.KOTLIN_2_3
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

val verifyOfflineAssetInputs by tasks.registering {
    group = "verification"
    description = "Refuses to build an APK without the verified official AAR and Kokoro payload."
    doLast {
        val required = listOf(
            rootProject.file("app/libs/sherpa-onnx-1.13.4.aar"),
            rootProject.file(".local-models/kokoro-int8-multi-lang-v1_1/model.int8.onnx"),
            rootProject.file(".local-models/kokoro-int8-multi-lang-v1_1/voices.bin"),
            rootProject.file(".local-models/kokoro-int8-multi-lang-v1_1/lexicon-zh.txt"),
            rootProject.file(".local-models/kokoro-int8-multi-lang-v1_1/models-manifest.json"),
        )
        val missing = required.filterNot { it.isFile }
        check(missing.isEmpty()) {
            "Offline build inputs are missing. Run scripts/build-deliverable.ps1. Missing: ${missing.joinToString()}"
        }
    }
}

val syncOfflineAssets by tasks.registering(Sync::class) {
    dependsOn(verifyOfflineAssetInputs)
    from(rootProject.file(".local-models/kokoro-int8-multi-lang-v1_1"))
    into(layout.buildDirectory.dir("generated/offlineAssets/kokoro"))
}

tasks.named("preBuild").configure {
    dependsOn(syncOfflineAssets)
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(files("libs/sherpa-onnx-1.13.4.aar"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.session)
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
    implementation(libs.readium.navigator.media.tts)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
