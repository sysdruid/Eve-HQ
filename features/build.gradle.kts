// Test-only module: feature scenarios against the real use cases and a real in-memory H2.
// Fakes only at true boundaries (SSO/ESI, clock). See TESTLIST.md conventions.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(project(":core"))
    testImplementation(project(":data"))
    testImplementation(libs.jdbscript)
    testImplementation(libs.h2)
    testImplementation(libs.flyway.core)
}
