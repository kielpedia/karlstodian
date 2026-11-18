plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
    id("io.ktor.plugin") version "3.1.2"
    application
}

group = "org.karlpedia"
version = "0.2.0"

repositories { mavenCentral() }

val ktor = "3.1.2"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-cio:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-server-status-pages:$ktor")
    implementation("io.ktor:ktor-client-core:$ktor")
    implementation("io.ktor:ktor-client-cio:$ktor")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    implementation("com.cronutils:cron-utils:9.2.1")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktor")
    testImplementation("io.ktor:ktor-client-mock:$ktor")
}

kotlin { jvmToolchain(21) }

application { mainClass.set("karlstodian.MainKt") }

ktor { fatJar { archiveFileName.set("karlstodian.jar") } }

tasks.test { useJUnitPlatform() }

// build/karlstodian-deploy.tgz: jar + installer (+ data/ if present) for the LXC. Holds your API keys.
tasks.register<Tar>("deployBundle") {
    dependsOn("buildFatJar")
    archiveFileName.set("karlstodian-deploy.tgz")
    destinationDirectory.set(layout.buildDirectory)
    compression = Compression.GZIP
    into("karlstodian-deploy") {
        from("deploy") { include("*.sh"); filePermissions { unix("755") } }
        from(layout.buildDirectory.file("libs/karlstodian.jar"))
        from("data") { into("data"); exclude("*.db-journal", "*.db-wal", "*.db-shm") }
    }
}
