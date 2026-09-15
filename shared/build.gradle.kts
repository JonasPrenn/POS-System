import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // Nicht com.android.library: Seit AGP 9 verweigert das Plugin die Zusammenarbeit mit
    // org.jetbrains.kotlin.multiplatform. Das Android-Ziel wird stattdessen unten ueber
    // kotlin { android {} } eingerichtet, der fruehere android {}-Block entfaellt.
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.androidx.room)
}

kotlin {
    android {
        namespace = "com.example.vereins_kassensystem.shared"
        compileSdk = 37
        minSdk = 26
        // Laesst commonTest auch auf der JVM laufen; ohne das kaeme :shared:allTests nur
        // ueber den iOS-Simulator an die Tests, und der ist der langsamere Weg.
        withHostTest {}
    }

    // Zwei Ziele: iPhone/iPad als Geraet (arm64) und der Simulator auf Apple Silicon.
    // Den Intel-Simulator (iosX64) gibt es nicht mehr: Compose 1.12, Lifecycle 2.11 und
    // Navigation 2.10 veroeffentlichen dafuer keine Artefakte, und ein Ziel ohne
    // Bibliotheken bricht die Aufloesung fuer alle Source-Sets.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            // Statisch, weil das SumUp-iOS-SDK als Framework danebenliegt und ein
            // dynamisches Shared-Framework die Symbolaufloesung unnoetig verkompliziert.
            isStatic = true
        }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        // Wie schon im alten App-Modul: Die Bildschirme benutzen TopAppBar & Co. ohne
        // eigenes , das Flag gilt fuer alle Ziele gemeinsam.
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }

    sourceSets {
        commonMain.dependencies {
            // Feste Koordinaten statt der Plugin-Accessors (compose.runtime usw.): Die
            // sind seit CMP 1.12 veraltet und zeigen ohnehin nur auf genau diese
            // Artefakte. Die Begruendung fuer die Versionen steht im Katalog.
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)

            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.navigation.compose)

            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.logging)

            implementation(libs.coil3.compose)
            implementation(libs.coil3.network.ktor)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            // Das SumUp-SDK verlangt androidx.compose.material ohne Versionsangabe und
            // verlaesst sich auf eine BOM. Die muss deshalb hier mit hinein.
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)

            // Nur unter Android: Hintergrundsicherung, Dokumentbaum-Zugriff und das
            // SumUp-Android-SDK. Auf iOS haben alle drei eigene Entsprechungen.
            implementation(libs.androidx.work.runtime.ktx)
            implementation(libs.androidx.documentfile)
            implementation(libs.androidx.security.crypto)
            // Als Zeichenkette, weil der Multiplatform-Dependency-Handler keinen
            // Katalog-Provider mit Konfigurationsblock annimmt; der Ausschluss muss aber
            // bleiben, sonst zieht das SDK einen Loyalty-Stub mit.
            implementation("com.sumup:merchant-sdk:${libs.versions.sumup.get()}") {
                exclude(group = "com.sumup.loyalty", module = "stub")
            }
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

// Room braucht seinen Prozessor auf jedem Ziel einzeln; ein blosses ksp(...) im
// commonMain reicht bei Multiplatform nicht aus.
dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
}
