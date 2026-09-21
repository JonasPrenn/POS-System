import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Der Server nach docs/VereinsDeckel-Server-und-API.pdf: PostgreSQL, ein HTTP-Dienst, zwei
// Sync-Endpunkte plus Geraeteanmeldung. Reine JVM ohne Android — laeuft auf jedem Rechner
// mit Java 17 oder neuer, auch auf einem Raspberry Pi.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // JsonNamingStrategy (snake_case auf dem Draht, camelCase im Code) ist in
        // kotlinx.serialization noch als experimentell markiert.
        freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
    }
}

// Gleicher Bytecode-Stand wie :core; ohne die Java-Seite wuerde der Kotlin-Compiler den
// Unterschied zur laufenden JVM (21) als Fehler melden.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("com.example.vereins_kassensystem.server.MainKt")
    applicationName = "vereinsdeckel-server"
}

dependencies {
    // Schluessel (UUIDv7) und die Saldoregel kommen aus :core — dieselben wie in der App.
    implementation(projects.core)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.rate.limit)
    // Die Web-Verwaltung ist server-gerendertes HTML (docs/WEB-VERWALTUNG.md, Kapitel 3).
    implementation(libs.ktor.server.html.builder)
    // Hinter Caddy ist jede Anfrage „vom Proxy“; die Begrenzung der Anmeldeversuche braucht die echte Adresse.
    implementation(libs.ktor.server.forwarded.header)

    implementation(libs.hikari)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgres.jdbc)

    // Argon2id fuer die Geraetetoken, in reinem Java — keine nativen Bibliotheken, die auf
    // einem ARM-Server erst einmal fehlen.
    implementation(libs.bouncycastle)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    // Echter PostgreSQL fuer die Tests: das Schema lebt von Triggern und einer Sicht, die
    // sich mit einer anderen Datenbank nicht nachstellen lassen.
    testImplementation(enforcedPlatform(libs.embedded.postgres.bom))
    testImplementation(libs.embedded.postgres)
    testRuntimeOnly(libs.embedded.postgres.darwin.arm64)
    testRuntimeOnly(libs.embedded.postgres.linux.arm64)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
