import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    `java-library`
    jacoco
    alias(libs.plugins.spotless)
    alias(libs.plugins.errorprone)
    alias(libs.plugins.maven.publish)
}

group = "io.github.jbburns"
description = "A pass-through JDBC driver that runs one statement against many databases and merges the results."

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories {
    mavenCentral()
}

dependencies {
    // Annotations only; retention is CLASS so nothing is needed at runtime.
    compileOnly(libs.jspecify)

    errorprone(libs.errorprone.core)
    errorprone(libs.nullaway)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    // Two deliberately different real JDBC drivers used as fake backends in tests.
    testImplementation(libs.h2)
    testImplementation(libs.sqlite.jdbc)
}

// The published POM must have no runtime dependencies: the jar is dropped into SQL clients
// next to the vendor drivers and nothing resolves transitive dependencies there.
configurations.all {
    if (name == "runtimeClasspath" || name == "apiElements" || name == "runtimeElements") {
        resolutionStrategy.failOnVersionConflict()
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Werror"))
    options.errorprone {
        disableWarningsInGeneratedCode = true
        error("NullAway")
        option("NullAway:OnlyNullMarked", "true")
        option("NullAway:JSpecifyMode", "true")
    }
    if (name.contains("Test", ignoreCase = true)) {
        options.errorprone.disable("NullAway")
        // Tests hold try-with-resources handles purely for their close() side effect.
        options.compilerArgs.add("-Xlint:-try")
    }
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        addBooleanOption("Xdoclint:all,-missing", true)
        addStringOption("Xwerror", "-quiet")
    }
}

tasks.jar {
    manifest {
        attributes(
            "Automatic-Module-Name" to "io.github.jbburns.manyfold.jdbc",
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "jbburns",
        )
    }
}

// Reproducible archives: identical inputs produce byte-identical jars.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    dirPermissions { unix("rwxr-xr-x") }
    filePermissions { unix("rw-r--r--") }
}

val mockitoAgentArgs = listOf("-XX:+EnableDynamicAgentLoading", "-Xshare:off")

tasks.test {
    useJUnitPlatform()
    jvmArgs(mockitoAgentArgs)
    finalizedBy(tasks.jacocoTestReport)
}

// Everything needed to try the driver in a SQL client with no other database installed: the
// driver jar plus the H2 jar (a test-only dependency, so it is resolved from the test classpath).
val clientBundle = tasks.register<Copy>("clientBundle") {
    description = "Copies the driver jar and the H2 jar into build/client for use in a SQL client."
    group = "distribution"
    val h2Jar = configurations.testRuntimeClasspath.get().incoming.artifactView {
        componentFilter { id ->
            id is ModuleComponentIdentifier && id.group == "com.h2database" && id.module == "h2"
        }
    }.files
    val outDir = layout.buildDirectory.dir("client")
    from(tasks.jar)
    from(h2Jar)
    into(outDir)
    doLast {
        val names = source.files.map { it.name }.sorted()
        names.forEach { println("client bundle: ${outDir.get().asFile.resolve(it)}") }
    }
}

// The bytecode targets Java 17, so the same test classes are run once more on a JDK 17 launcher.
val test17 = tasks.register<Test>("test17") {
    description = "Runs the unit tests on a Java 17 runtime."
    group = "verification"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(17) }
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    jvmArgs(mockitoAgentArgs)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.check {
    // Javadoc runs with -Werror so a broken reference fails CI rather than the release.
    dependsOn(test17, tasks.jacocoTestReport, tasks.javadoc)
}

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat(libs.versions.google.java.format.get())
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("misc") {
        target("*.md", "*.toml", "*.properties", ".editorconfig", ".gitignore", ".github/**/*.yml", ".github/**/*.md")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

mavenPublishing {
    // automaticRelease defaults to false: the deployment is validated and uploaded, then a person
    // presses "Publish" in the Central Portal. Pass -PmavenCentralAutomaticPublishing=true to
    // release without that step.
    publishToMavenCentral()
    signAllPublications()

    coordinates(group.toString(), "manyfold-jdbc", version.toString())

    pom {
        name = "manyfold-jdbc"
        description = project.description
        inceptionYear = "2026"
        url = "https://github.com/jbburns/manyfold-jdbc"
        licenses {
            license {
                name = "Apache-2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "jbburns"
                name = "jbburns"
                url = "https://github.com/jbburns"
            }
        }
        scm {
            url = "https://github.com/jbburns/manyfold-jdbc"
            connection = "scm:git:https://github.com/jbburns/manyfold-jdbc.git"
            developerConnection = "scm:git:ssh://git@github.com/jbburns/manyfold-jdbc.git"
        }
        issueManagement {
            system = "GitHub"
            url = "https://github.com/jbburns/manyfold-jdbc/issues"
        }
    }
}
