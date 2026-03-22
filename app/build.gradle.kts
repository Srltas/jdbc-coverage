plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    kotlin("kapt") version libs.versions.kotlin.get()
}

repositories {
    mavenCentral()
}

dependencies {
    // JavaParser + Symbol Solver
    implementation(libs.javaparser.symbol.solver)

    // CLI
    implementation(libs.picocli)
    kapt(libs.picocli.codegen)

    // YAML/JSON
    implementation(libs.jackson.kotlin)
    implementation(libs.jackson.yaml)
    implementation(libs.jackson.jsr310)

    // HTML report (v0.2)
    implementation(libs.kotlinx.html)

    // Test
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "com.jdbcchecker.cli.MainKt"
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.jdbcchecker.cli.MainKt"
    }
}

tasks.register<Jar>("fatJar") {
    archiveBaseName = "jdbc-checker"
    archiveVersion = "1.0.0"
    archiveClassifier = "all"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "com.jdbcchecker.cli.MainKt"
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })
}
