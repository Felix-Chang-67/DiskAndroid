plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.felixchang67.diskandroid.core.player"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        ndk { abiFilters += "arm64-v8a" }
        consumerProguardFiles("consumer-rules.pro")
    }

    // 原生播放器（native/diskplayer）：libdvdnav / libdvdread + FFmpeg，静态链接进 libdiskplayer.so
    externalNativeBuild {
        cmake {
            path = file("../../native/diskplayer/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// 第三方静态库（不进仓库）：缺的时候先编译（下载固定版本的源码并核对 SHA-256，要几分钟）
val thirdPartyMarker = rootProject.file("native/third_party/out/android-arm64/lib/libavcodec.a")
val ndkDir = androidComponents.sdkComponents.ndkDirectory
val buildThirdParty by tasks.registering(Exec::class) {
    description = "编译播放器用的第三方库（libdvdnav、libdvdread、libbluray、FFmpeg）"
    onlyIf { !thirdPartyMarker.exists() }
    commandLine("bash", rootProject.file("native/third_party/build.sh").absolutePath, "android-arm64")
    doFirst {
        environment("ANDROID_NDK_HOME", ndkDir.get().asFile.absolutePath)
    }
}
tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake") || name.startsWith("externalNativeBuild")) {
        dependsOn(buildThirdParty)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}
