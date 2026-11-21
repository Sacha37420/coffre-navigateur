// Module Kotlin pur : format du coffre, chiffrement, générateur. Testé sur la JVM, sans téléphone.
// org.json est fourni par Android à l'exécution ; sur la JVM, il ne sert qu'à compiler et tester.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Bytecode Java 17 (ce que l'application Android consomme), quel que soit le JDK qui construit.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    compileOnly("org.json:json:20260814")
    testImplementation("org.json:json:20260814")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // Vecteurs croisés avec l'extension (tests/vecteurs/v1.json) et vérification par Node.
    systemProperty("coffre.racine", rootProject.projectDir.parentFile.absolutePath)
    testLogging { events("passed", "failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
