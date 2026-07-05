plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // JavaParser + Symbol Solver
    implementation(libs.javaparser.symbol.solver)

    // CLI
    implementation(libs.picocli)

    // YAML/JSON
    implementation(libs.jackson.kotlin)
    implementation(libs.jackson.yaml)
    implementation(libs.jackson.jsr310)

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
    applicationName = "jdbc-checker"
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.jdbcchecker.cli.MainKt"
    }
}
