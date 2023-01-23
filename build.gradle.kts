@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.InetAddress
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

plugins {
    kotlin("jvm") version "1.8.0"
    `jvm-test-suite`
    `maven-publish`
    jacoco
    id("org.sonarqube") version "3.5.0.2730"
}

group = "com.demo"
version = "0.1"
java.sourceCompatibility = JavaVersion.VERSION_11

repositories {
    mavenCentral()
}

val jmxAgent: Configuration by configurations.creating

dependencies {
    // basic Kotlin dependencies
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")

    // Arrow functional library
    implementation("io.arrow-kt:arrow-core-jvm:1.1.3")
    implementation("io.arrow-kt:arrow-fx-coroutines-jvm:1.1.3")

    // Configuration HOCON library
    implementation("com.typesafe:config:1.4.2")

    // Logging & Monitoring
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.4")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:2.19.0")
    implementation("org.apache.logging.log4j:log4j-core:2.19.0")
    implementation("io.prometheus:simpleclient_httpserver:0.16.0")

    // Kafka dependencies
    implementation("org.apache.kafka:kafka-clients:3.3.1")

    // IBM MQ dependencies
    implementation("com.ibm.mq:com.ibm.mq.allclient:9.1.0.11")

    // SOAP dependencies
    adjustApacheCxfTransitiveDependencies()
    // ATTENTION: do NOT just update these version numbers from apache CXF! Make sure that all profile dependencies
    // are identical - or update them accordingly. If you approve a PR with changes to this - KNOW WHAT YOU ARE DOING!
    implementation("org.apache.cxf:cxf-rt-frontend-jaxws:3.5.5")
    implementation("org.apache.cxf:cxf-rt-transports-http:3.5.5")
    implementation("org.apache.cxf:cxf-rt-transports-http-jetty:3.5.5")

    // Jackson
    implementation("com.fasterxml.jackson.core:jackson-core:2.14.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.14.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.14.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.14.0")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.14.0")
    implementation("com.fasterxml.woodstox:woodstox-core:6.4.0")

    // Data transformation
    implementation("org.redundent:kotlin-xml-builder:1.8.0")
    implementation("com.bazaarvoice.jolt:jolt-core:0.1.7")

    // test dependencies
    testImplementation(kotlin("test-junit"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.6.4")
    testImplementation("io.kotest:kotest-assertions-core-jvm:5.5.4")
    testImplementation("io.kotest:kotest-assertions-json-jvm:5.5.4")
    testImplementation("io.kotest.extensions:kotest-assertions-arrow-jvm:1.3.0")
    testImplementation("io.mockk:mockk:1.13.2")

    jmxAgent("io.prometheus.jmx:jmx_prometheus_javaagent:0.17.2")
}

publishing {
    publications.create<MavenPublication>("maven") {
        from(components["kotlin"])
    }
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = java.sourceCompatibility.toString()
        freeCompilerArgs = listOf(
            "-opt-in=kotlin.RequiresOptIn",
        )
    }
}

tasks.withType<Jar>().configureEach {
    manifest {
        attributes["Main-Class"] = "com.bmw.otd.agathe.AgatheKt"
        attributes["Class-Path"] =
            configurations.runtimeClasspath.map { it.files.joinToString(" ") { f -> "dependencies/${f.name}" } }
    }
}

tasks.register<Copy>("copyDependencies") {
    from(configurations.runtimeClasspath)
    from(layout.projectDirectory.dir("lib/jco")) { include("*.so", "*.dll", "*.dylib") }
    into(layout.buildDirectory.dir("dependencies"))
}

testing {
    suites {
        val jUnitVersion = "5.9.1"
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter(jUnitVersion)
        }

        val integrationTest by registering(JvmTestSuite::class)
        integrationTest {
            val testContainersVersion = "1.17.5"
            dependencies {
                implementation(project)
                implementation("org.testcontainers:testcontainers:$testContainersVersion")
                implementation("org.testcontainers:junit-jupiter:$testContainersVersion")
                implementation("org.testcontainers:kafka:$testContainersVersion")
                implementation("org.testcontainers:toxiproxy:$testContainersVersion")
            }

            targets {
                all {
                    testTask.configure {
                        shouldRunAfter(test)
                    }
                }
            }
        }
    }
}

val integrationTestImplementation: Configuration by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}

tasks.check {
    dependsOn(testing.suites.named("integrationTest"))
    finalizedBy(tasks.jacocoTestReport) // report is always generated after tests run
}

tasks.jacocoTestReport {
    dependsOn(tasks.check) // tests are required to run before generating the report

    // collect all exec files and generate reports in xml and html
    executionData.setFrom(layout.buildDirectory.asFileTree.matching { include("/jacoco/*.exec") })
    reports {
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/all-tests/jacocoAllTestReport.xml"))
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/all-tests/html"))
    }
}

jacoco {
    toolVersion = "0.8.8"
}

tasks.sonarqube {
    dependsOn(tasks.check)
}

sonarqube {
    properties {
        properties["sonar.sourceEncoding"] = "UTF-8"
        properties["sonar.qualitygate.wait"] = true

        properties["sonar.coverage.jacoco.xmlReportPaths"] =
            tasks.jacocoTestReport.get().reports.xml.outputLocation.get()
        properties["sonar.junit.reportPaths"] =
            tasks.withType<Test>().map { it.reports.junitXml.outputLocation.get() }
        properties["sonar.tests"] =
            sourceSets.filter { it.name in listOf("test", "integrationTest") }
                .flatMap { it.allJava.srcDirs }.filter { it.exists() }
    }
}

/**
 * Work Around the Maven pom profile activated transitive dependencies of JDK 9+ from Apache CXF
 */
fun DependencyHandlerScope.adjustApacheCxfTransitiveDependencies() {
    // See https://discuss.gradle.org/t/apache-cxf-transitive-dependencies-are-missing-many-dependencies-when-coming-from-maven-to-gradle/42333
    components {
        all {
            // add missing transitive dependencies for Apache CXF
            if (id.group != "org.apache.cxf") {
                return@all
            }
            check(id.version == "3.5.5") {
                "Please configure Java 9+ dependencies for $id - take them from apache.cxf.parent (search for <id>java9-plus</id>)"
                // ATTENTION: do NOT just update these version numbers from apache CXF! Make sure that all profile dependencies
                // are identical - or update them accordingly. If you approve a PR with changes to this - KNOW WHAT YOU ARE DOING!
                // Search for dependencies in the profile "java9-plus" and check the dependent versions (runtime/compile)
                // Github link https://github.com/apache/cxf/blob/cxf-3.5.5/parent/pom.xml#L2300
            }
            listOf("compile", "runtime").forEach { variant ->
                withVariant(variant) {
                    withDependencies {
                        add("jakarta.annotation:jakarta.annotation-api:1.3.5")
                        add("jakarta.xml.ws:jakarta.xml.ws-api:2.3.3")
                        add("jakarta.jws:jakarta.jws-api:2.1.0")
                        add("jakarta.xml.soap:jakarta.xml.soap-api:1.4.2")
                        add("com.sun.activation:jakarta.activation:1.2.2")
                        add("org.apache.geronimo.specs:geronimo-jta_1.1_spec:1.1.1")
                    }
                }
            }
            withVariant("runtime") {
                withDependencies {
                    add("com.sun.xml.messaging.saaj:saaj-impl:1.5.3")
                }
            }
        }
    }
}

object Metadata {
    val hostName = runCatching { InetAddress.getLocalHost().hostName }.getOrNull() ?: "<unknown host>"
    val buildDateTimeStamp = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES).toString()
}
