// Pure Kotlin domain: no UI, DB or HTTP dependencies. Keep it that way.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(libs.kotlinx.coroutines)
}
