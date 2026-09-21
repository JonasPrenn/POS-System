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
