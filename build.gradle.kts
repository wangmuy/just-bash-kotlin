plugins {
    kotlin("jvm") version "1.9.24"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.justbash"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    // coroutine
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    // awk
    implementation("io.jawk:jawk:7.1.00")
    // jq
    implementation("net.thisptr:jackson-jq:1.6.2")
    // diff
    implementation("io.github.java-diff-utils:java-diff-utils:4.15")
    // yq
    implementation("org.yaml:snakeyaml:2.2")
    // tar/gzip
    implementation("org.apache.commons:commons-compress:1.28.0")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// shadow (fat) jar — same as maven-shade-plugin
tasks.shadowJar {
    archiveClassifier.set("")
    manifest {
        attributes["Main-Class"] = "com.justbash.cli.JustBashCli"
    }
    // exclude duplicate META-INF files from dependencies
    mergeServiceFiles()
}

// make `gradle build` produce the fat jar
tasks.build {
    dependsOn(tasks.shadowJar)
}