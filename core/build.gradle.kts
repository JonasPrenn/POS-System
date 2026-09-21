import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :core ist das, was App und Server gemeinsam rechnen: Schluessel, Zeit, Zahlformate —
// und mit Schritt 7 die Bestandslogik. Kein Compose, kein Room, keine Plattform-API.
// Deshalb ein reines JVM-Ziel statt eines Android-Ziels: Android konsumiert die
// JVM-Variante wie bei kotlinx-datetime auch, und der Server braucht ohnehin nur die JVM.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
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

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.datetime)
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
