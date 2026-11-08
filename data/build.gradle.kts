// Adapters for core's ports: ESI/SSO client and H2 persistence.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.flyway.core)
    implementation(libs.h2)
}
