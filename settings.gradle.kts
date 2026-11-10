pluginManagement {
    repositories {
        google()
        // Google 托管的 Maven Central 镜像（官方 Central 对共享出口 IP 有频率限制，会返回 429）
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralMirror" }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralMirror" }
        mavenCentral()
    }
}

rootProject.name = "DiskAndroid"

include(":app")
include(":core:log")
include(":core:scsi")
include(":core:mmc")
include(":core:drive")
include(":core:cdda")
include(":core:meta")
include(":core:rip")
include(":core:fs")
include(":core:video")
include(":core:player")
include(":core:testing")
include(":core:usb")
