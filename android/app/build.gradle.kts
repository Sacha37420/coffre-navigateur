import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Adresse du lab, fixée à la construction comme pour l'extension : variable LAB_URL, sinon
// ../config.local.json (non versionné). L'APK ne revendique l'adresse de retour (App Links) que de ce lab.
val labUrl: String = (System.getenv("LAB_URL")?.takeIf { it.isNotBlank() }
    ?: rootProject.file("../config.local.json").takeIf { it.exists() }?.readText()
        ?.let { Regex("\"labUrl\"\\s*:\\s*\"([^\"]*)\"").find(it)?.groupValues?.get(1) }
    ?: "").trim().trimEnd('/')
val labHost: String = if (labUrl.isEmpty()) "" else URI(labUrl).also {
    require(it.scheme == "https" && it.host != null && (it.path.isNullOrEmpty() || it.path == "/")) { "LAB_URL doit être https://domaine" }
}.host

android {
    namespace = "io.github.sacha37420.coffre"
    compileSdk = 36

    defaultConfig {
        // Identifiant définitif : le changer après une première installation crée une autre application.
        applicationId = "io.github.sacha37420.coffre"
        minSdk = 26          // AutofillService (lot 11)
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.0"
        // Sans adresse de lab, l'hôte « lab.invalid » (réservé, RFC 2606) ne correspond à rien : connexion désactivée.
        manifestPlaceholders["labHost"] = labHost.ifEmpty { "lab.invalid" }
        resValue("string", "lab_origin", if (labHost.isEmpty()) "" else "https://$labHost")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        // Robolectric (tests JVM des écrans) a besoin des ressources de l'application.
        unitTests.isIncludeAndroidResources = true
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // Aucune bibliothèque tierce : le module core (format du coffre) et la plateforme Android.
    implementation(project(":core"))
    // Tests seulement : rien de ceci n'entre dans l'APK.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}

tasks.withType<Test>().configureEach {
    systemProperty("coffre.racine", rootProject.projectDir.parentFile.absolutePath)
}
