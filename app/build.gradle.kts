plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val gitSha: String = providers.exec {
    commandLine("git", "rev-parse", "--short=8", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().ifEmpty { "nogit" }

val gitDirty: Boolean = providers.exec {
    commandLine("git", "status", "--porcelain", "--untracked-files=no")
    isIgnoreExitValue = true
}.standardOutput.asText.get().isNotBlank()

android {
    namespace = "io.github.felixchang67.diskandroid"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "io.github.felixchang67.diskandroid"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // 每个测试节点递增 versionCode，保证可以直接覆盖安装。
        versionCode = 8
        versionName = "0.7.0-N2.1"

        buildConfigField("String", "GIT_SHA", "\"$gitSha${if (gitDirty) "+dirty" else ""}\"")
        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
        buildConfigField("String", "TEST_NODE", "\"N2.1\"")

        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // 仅用于测试的固定签名（见 keystore/README.md）：云端每次构建的 APK 都能直接覆盖安装。
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug-test.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        // 发给真机测试的是 release：R8 裁剪（不混淆）+ 测试签名，体积小、Compose 运行快。
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    testOptions {
        // Robolectric 冒烟测试需要合并后的资源和清单
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric 运行时会下载 android-all；官方 Central 对共享出口 IP 限流，改走 Google 镜像
            it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
            it.systemProperty("robolectric.dependency.repo.id", "central-mirror")
        }
    }
}

dependencies {
    implementation(project(":core:log"))
    implementation(project(":core:usb"))
    implementation(project(":core:drive"))
    implementation(project(":core:cdda"))
    implementation(project(":core:meta"))
    implementation(project(":core:rip"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(project(":core:testing"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
