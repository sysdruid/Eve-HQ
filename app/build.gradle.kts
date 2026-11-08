plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.coroutines.swing)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.h2)
}

compose.desktop {
    application {
        mainClass = "eve.hq.app.MainKt"
    }
}
