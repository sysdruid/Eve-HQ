// Adapters for core's ports: ESI/SSO client and H2 persistence.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.jooq.codegen)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.flyway.core)
    implementation(libs.h2)
    implementation(libs.jooq)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.ktor.client.mock)

    // Generates jOOQ classes straight from the Flyway migration scripts (no live database needed),
    // so the migrations stay the single source of truth for the schema.
    jooqCodegen(libs.jooq.meta.extensions)
}

jooq {
    configuration {
        generator {
            database {
                name = "org.jooq.meta.extensions.ddl.DDLDatabase"
                properties {
                    property {
                        key = "scripts"
                        value = "src/main/resources/db/migration/*.sql"
                    }
                    property {
                        key = "sort"
                        value = "flyway"
                    }
                    property {
                        key = "defaultNameCase"
                        value = "lower"
                    }
                }
            }
            target {
                packageName = "eve.hq.data.jooq"
            }
        }
    }
}

// The plugin only generates; wire the output into compilation ourselves.
sourceSets.main {
    java.srcDir(layout.buildDirectory.dir("generated-sources/jooq"))
}
tasks.named("compileKotlin") { dependsOn("jooqCodegen") }
tasks.named("compileJava") { dependsOn("jooqCodegen") }
