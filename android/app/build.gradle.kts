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

// Version : celle de l'étiquette publiée (COFFRE_VERSION, ex. 0.5.0), sinon celle de package.json, pour que
// l'APK et les extensions portent toujours le même numéro.
val coffreVersion: String = System.getenv("COFFRE_VERSION")?.takeIf { it.isNotBlank() }
    ?: Regex("\"version\"\\s*:\\s*\"([0-9.]+)\"").find(rootProject.file("../package.json").readText())!!.groupValues[1]
val coffreVersionCode: Int = coffreVersion.split(".").map { it.toInt() }.let { (a, b, c) -> a * 10_000 + b * 100 + c }

// Signature de publication : seulement si la clé est fournie (secrets du dépôt, voir publication.yml).
// Sans elle, `assembleRelease` produit une APK non signée, que l'on ne publie pas.
val releaseKeystore: String? = System.getenv("COFFRE_KEYSTORE")?.takeIf { it.isNotBlank() && file(it).exists() }

android {
    namespace = "io.github.sacha37420.coffre"
    compileSdk = 36

    defaultConfig {
        // Identifiant définitif : le changer après une première installation crée une autre application.
        applicationId = "io.github.sacha37420.coffre"
        minSdk = 26          // AutofillService (lot 11)
        targetSdk = 36
        versionCode = coffreVersionCode
        versionName = coffreVersion
        // Sans adresse de lab, l'hôte « lab.invalid » (réservé, RFC 2606) ne correspond à rien : connexion désactivée.
        manifestPlaceholders["labHost"] = labHost.ifEmpty { "lab.invalid" }
        resValue("string", "lab_origin", if (labHost.isEmpty()) "" else "https://$labHost")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("publication") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("COFFRE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("COFFRE_KEY_ALIAS")
                keyPassword = System.getenv("COFFRE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("publication")
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
