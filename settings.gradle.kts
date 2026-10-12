plugins {
    // Lets Gradle download JDKs 11 and 17 for the test11 and test17 tasks when none is installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "manyfold-jdbc"
