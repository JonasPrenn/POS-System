import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :core ist das, was App und Server gemeinsam rechnen und sprechen: Schluessel, Zeit,
// Zahlformate, Saldo- und Bestandsregeln und das Drahtformat des Abgleichs. Kein Compose,
// kein Room, keine Plattform-API. Deshalb ein reines JVM-Ziel statt eines Android-Ziels:
// Android konsumiert die JVM-Variante wie bei kotlinx-datetime auch, und der Server
// braucht ohnehin nur die JVM.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm {
        // Bytecode-Stand 17, damit der Android-Build von :shared die Klassen ohne
        // Ueberraschungen dexen kann; der Server laeuft damit ebenso.
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Dieselben zwei Apple-Ziele wie :shared; ohne sie koennte :shared nicht abhaengen.
    iosArm64()
    iosSimulatorArm64()

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        // JsonNamingStrategy (snake_case auf dem Draht, camelCase im Code) ist in
        // kotlinx.serialization noch als experimentell markiert.
        freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.datetime)
            // Das Drahtformat und der HTTP-Client des Abgleichs. api, weil App und Server
            // die Typen (JsonObject, HttpClient) in ihren eigenen Signaturen brauchen.
            // Eine Engine bringt :core nicht mit — die waehlt, wer den Client baut.
            api(libs.ktor.client.core)
            api(libs.ktor.client.content.negotiation)
            api(libs.ktor.serialization.kotlinx.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Ohne Java-Quellen bliebe die Java-Seite auf dem Stand der laufenden JVM (21) und der
// Kotlin-Compiler weigert sich, dazu ein anderes jvmTarget zu uebersetzen.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// AppVersion.kt aus VERSION im Repo, damit App und Server dieselbe Nummer zeigen — erzeugt,
// nicht eingecheckt; wer VERSION aendert (docs/tools/version.sh), bekommt sie beim naechsten Build.
val generateVersion by tasks.registering {
    val versionFile = rootProject.file("VERSION")
    val outDir = layout.buildDirectory.dir("generated/version/commonMain/kotlin")
    val number = rootProject.extra["vdVersionNumber"] as String
    val stage = rootProject.extra["vdVersionStage"] as String
    val label = rootProject.extra["vdVersionLabel"] as String
    val code = rootProject.extra["vdVersionCode"] as Int
    inputs.file(versionFile)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().file("com/example/vereins_kassensystem/AppVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package com.example.vereins_kassensystem
            |
            |/** Erzeugt aus VERSION im Repo (core/build.gradle.kts) — nicht von Hand aendern, beim naechsten Build waere es weg. */
            |object AppVersion {
            |    /** x.y.z */
            |    const val NUMBER = "$number"
            |    /** "beta" in der Entwicklung, leer nach der Freigabe durch den Besitzer. */
            |    const val STAGE = "$stage"
            |    /** Was Menschen lesen: "1.2.0 Beta" oder "1.2.0". */
            |    const val LABEL = "$label"
            |    /** Android versionCode und iOS CURRENT_PROJECT_VERSION; steigt mit jeder Nummer. */
            |    const val CODE = $code
            |    val isBeta: Boolean get() = STAGE.isNotEmpty()
            |}
            |""".trimMargin()
        )
    }
}
kotlin.sourceSets.named("commonMain") { kotlin.srcDir(generateVersion) }
