// 根工程只声明插件版本，不应用。
// AGP 9 内置 Kotlin 支持；这里声明 kotlin.jvm 也会把 KGP 升级到版本目录里指定的版本。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
