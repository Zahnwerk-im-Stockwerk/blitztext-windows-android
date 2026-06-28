plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Bau-Nummer kommt aus GitHub Actions (-PbuildNummer=<Laufnummer>), lokal Fallback 1.
// So hat jede APK eine sichtbare Version (1.0.<Lauf>) und Updates sind unterscheidbar.
val buildNummer = (project.findProperty("buildNummer") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "de.zahnwerk.blitztext"
    compileSdk = 34

    defaultConfig {
        applicationId = "de.zahnwerk.blitztext"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNummer
        versionName = "1.0.$buildNummer"
    }

    // Kein fester Signatur-Schluessel im oeffentlichen Repo: `assembleDebug` nutzt den
    // automatischen Android-Debug-Schluessel — jeder Build laeuft mit dem eigenen Schluessel
    // des Bauenden. Hinweis: ohne festen Schluessel kann man eine neue APK NICHT ueber eine
    // mit anderem Schluessel installierte druebersetzen (erst deinstallieren). Wer durchgaengige
    // Update-Signatur braucht, speist einen eigenen Keystore via GitHub-Secrets ein —
    // Anleitung in der README ("Eigene Signatur fuer Updates").
    buildTypes {
        debug {
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// Bewusst keine externen Bibliotheken: nur Android-Framework + Kotlin-Standardbibliothek.
// Weniger Abhaengigkeiten = weniger Download in der Cloud, nichts Fremdes in der App.
dependencies {
}
