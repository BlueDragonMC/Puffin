import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.7.10"
    kotlin("plugin.serialization") version "1.7.10"
    id("com.github.johnrengelman.shadow") version "7.1.2"
    application
}

group = "com.bluedragonmc"
version = "1.0.0"

repositories {
    mavenCentral()
    mavenLocal()
    maven(url = "https://jitpack.io")
}

dependencies {
    testImplementation(kotlin("test"))

    implementation("com.github.bluedragonmc:messages:a2a08c9d8e")
    implementation("com.github.bluedragonmc:messagingsystem:3abc4b8a49")

    implementation("ch.qos.logback:logback:0.5")
    implementation("ch.qos.logback:logback-classic:1.2.11")

    implementation("io.kubernetes:client-java:16.0.0")

    implementation("org.litote.kmongo:kmongo:4.6.1")
    implementation("org.litote.kmongo:kmongo-coroutine:4.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.4.0-RC")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "16"
}

application {
    mainClass.set("com.bluedragonmc.puffin.app.MainKt")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.bluedragonmc.puffin.app.MainKt"
    }
}