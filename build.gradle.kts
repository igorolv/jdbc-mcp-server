plugins {
    java
    antlr
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
}

dependencies {
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.ai.mcp.server)
    implementation(libs.jsqlparser)
    implementation(libs.sqlite.jdbc)
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation(libs.antlr4.runtime)
    antlr(libs.antlr4)

    // JDBC drivers bundled into the fat jar
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.ojdbc11)
    runtimeOnly(libs.orai18n)
    runtimeOnly(libs.mssql.jdbc)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.oracle.free)
    testImplementation(libs.testcontainers.mssqlserver)
    testImplementation(libs.commons.lang3)
    // Force all testcontainers modules to the same version to avoid version conflicts
    testImplementation("org.testcontainers:testcontainers:1.21.4")
    testImplementation("org.testcontainers:database-commons:1.21.4")
    testImplementation("org.testcontainers:jdbc:1.21.4")
}

tasks.jar {
    enabled = false
}

tasks.bootJar {
    archiveBaseName.set("jdbc-mcp-server")
    archiveVersion.set("")
    archiveClassifier.set("")
    manifest {
        attributes["Enable-Native-Access"] = "ALL-UNNAMED"
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.generateGrammarSource {
    arguments = arguments + listOf("-visitor")
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
