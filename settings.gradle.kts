pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.sumup.com/releases") }
    }
}

// Erlaubt projects.shared statt project(":shared") — ein Tippfehler faellt damit
// beim Uebersetzen auf und nicht erst beim Ausfuehren.
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "VereinsDeckel"

// :shared traegt alles, was auf beiden Plattformen gilt — Datenhaltung, Logik und die
// gesamte Oberflaeche. :androidApp ist nur noch die Huelle, die es unter Android startet;
// das Gegenstueck dazu ist iosApp/, das Xcode oeffnet und nicht von Gradle gebaut wird.
// :core ist der gemeinsame Nenner von App und Server: Schluessel, Zeit, Zahlformate, spaeter
// die Bestandslogik. Reines Kotlin ohne Compose und Room, damit der Server es benutzen kann.
include(":core")
include(":shared")
include(":androidApp")
