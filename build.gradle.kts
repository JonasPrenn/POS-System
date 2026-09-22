// Wurzelprojekt. Die Plugins werden hier nur bekannt gemacht und in den Modulen
// angewandt, damit alle dieselbe Version benutzen.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.google.devtools.ksp) apply false
    alias(libs.plugins.androidx.room) apply false
}

// Die Version steht genau einmal: in VERSION (x.y.z oder x.y.z-beta), geaendert nur ueber
// docs/tools/version.sh. Von hier bekommen Android (versionName, versionCode), :core
// (AppVersion, damit App und Server sie zeigen) und Gradle selbst dieselbe Nummer; iOS liest
// sie aus iosApp/Configuration/Version.xcconfig, das dasselbe Skript schreibt.
val versionText = rootProject.file("VERSION").readText().trim()
val versionNumber = versionText.substringBefore('-')
val versionStage = versionText.substringAfter('-', "")
val versionParts = versionNumber.split('.').map { it.toInt() }
require(versionParts.size == 3 && (versionStage.isEmpty() || versionStage == "beta")) { "VERSION muss x.y.z oder x.y.z-beta sein, nicht '$versionText'" }
extra["vdVersionNumber"] = versionNumber
extra["vdVersionStage"] = versionStage
extra["vdVersionLabel"] = if (versionStage.isEmpty()) versionNumber else "$versionNumber Beta"
// major*100000 + minor*1000 + patch*10, plus 9 bei Freigabe: Eine Beta liegt unter ihrer
// Freigabe, die Freigabe unter der naechsten Beta — Android und iOS verlangen steigende Codes.
extra["vdVersionCode"] = versionParts[0] * 100_000 + versionParts[1] * 1_000 + versionParts[2] * 10 + (if (versionStage.isEmpty()) 9 else 0)
allprojects { version = versionText }
