plugins {
    // Lets Gradle download a JDK 17 for the test17 task when none is installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "manyfold-jdbc"
