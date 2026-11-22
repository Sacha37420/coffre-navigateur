plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.sacha37420.coffre"
    compileSdk = 36

    defaultConfig {
        // Identifiant définitif : le changer après une première installation crée une autre application.
        applicationId = "io.github.sacha37420.coffre"
        minSdk = 26          // AutofillService (lot 11)
        targetSdk = 36
        versionCode = 3
        versionName = "0.2.1"
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
