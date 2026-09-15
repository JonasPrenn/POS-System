import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.androidx.room)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // Drei Ziele: iPhone/iPad als Geraet (arm64), der Simulator auf Apple Silicon und
    // der auf Intel-Macs. Wer nur auf einem Mac mit M-Chip baut, braucht iosX64 nicht,
    // aber es kostet nichts ausser Buildzeit und erspart spaeteres Nachruesten.
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
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
    }

    sourceSets {
        commonMain.dependencies {
            // Ueber die Plugin-Accessors statt ueber feste Koordinaten: seit CMP 1.10
            // liegen die Artefakte unter androidx.compose.* und das Plugin loest das
            // selbst auf. Haette man sie hier hart eingetragen, waere der naechste
            // Versionssprung ein Suchspiel.
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)

            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.navigation.compose)

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
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)

            // Nur unter Android: Hintergrundsicherung, Dokumentbaum-Zugriff und das
            // SumUp-Android-SDK. Auf iOS haben alle drei eigene Entsprechungen.
            implementation(libs.androidx.work.runtime.ktx)
            implementation(libs.androidx.documentfile)
            implementation(libs.androidx.security.crypto)
            implementation(libs.sumup.merchant.sdk) {
                exclude(group = "com.sumup.loyalty", module = "stub")
            }
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

android {
    namespace = "com.example.vereins_kassensystem.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

// Room braucht seinen Prozessor auf jedem Ziel einzeln; ein blosses ksp(...) im
// commonMain reicht bei Multiplatform nicht aus.
dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosX64", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
}
