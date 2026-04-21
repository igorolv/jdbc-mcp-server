plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "ru.it_spectrum.ai.jdbc.mcp"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    maven { url = uri("https://repo.spring.io/milestone") }
}

dependencies {
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.ai.mcp.server)

    // JDBC drivers bundled into the fat jar
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.ojdbc11)
    runtimeOnly(libs.orai18n)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.oracle.free)
    testImplementation(libs.commons.lang3)

    // Force all testcontainers modules to 1.20.4 to avoid version conflicts
    testImplementation("org.testcontainers:testcontainers:1.20.4")
    testImplementation("org.testcontainers:database-commons:1.20.4")
    testImplementation("org.testcontainers:jdbc:1.20.4")
}

tasks.jar {
    enabled = false
}

tasks.bootJar {
    archiveBaseName.set("jdbc-mcp-server")
    archiveVersion.set("")
    archiveClassifier.set("")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration", "live-oracle")
    }
}

tasks.register<Test>("integrationTest") {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    group = "verification"
    description = "Runs integration tests that require a live database (via Testcontainers)"
    shouldRunAfter(tasks.test)
}

tasks.register<Test>("liveOracleTest") {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("live-oracle")
    }
    group = "verification"
    description = "Runs read-only smoke tests against a real Oracle DB. Requires " +
            "LIVE_ORACLE_URL / LIVE_ORACLE_USERNAME / LIVE_ORACLE_PASSWORD env vars; " +
            "tests are skipped when unset."
    // Propagate LIVE_ORACLE_* from the invoking shell into the test JVM.
    listOf("LIVE_ORACLE_URL", "LIVE_ORACLE_USERNAME", "LIVE_ORACLE_PASSWORD",
        "LIVE_ORACLE_SCHEMA").forEach { key ->
        System.getenv(key)?.let { environment(key, it) }
    }
    outputs.upToDateWhen { false }  // always re-run — depends on external DB state
    shouldRunAfter(tasks.test)
}
