plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The app's version lives in gradle.properties (tvcal.version, semantic: major.minor.patch). The version
// code follows from it (0.1.0 -> 100, 1.2.3 -> 10203), so every release installs over the one before.
val appVersion = providers.gradleProperty("tvcal.version").get()
val appVersionCode = appVersion.split(".").map { it.toInt() }.let { (a, b, c) -> a * 10000 + b * 100 + c }
val appFileName = "TV-Cal-Probe-v$appVersion"

android {
    namespace = "tvcal.probe"
    compileSdk = 37

    defaultConfig {
        applicationId = "br.camarda.tvcal.probe"
        minSdk = 31
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersion
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":calibrationkit"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.lifecycle.runtime.compose)
}

// ./gradlew apk: the release build, named for the app and its version, in dist/ (TV-Cal-Probe-v0.1.0.apk).
// Signed with the debug key for now, so it installs over a debug build from the same machine.
tasks.register<Copy>("apk") {
    group = "distribution"
    description = "Builds the release APK as dist/$appFileName.apk"
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release")) { include("*.apk") }
    into(rootProject.layout.projectDirectory.dir("dist"))
    rename { "$appFileName.apk" }
}
