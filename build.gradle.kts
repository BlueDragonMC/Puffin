import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.7.10"
    application
}

group = "com.bluedragonmc"
version = "0.1.0"

repositories {
    mavenCentral()
    mavenLocal()
    maven(url = "https://jitpack.io")
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("com.github.bluedragonmc:messages:5d3dd0d240")
    implementation("com.github.bluedragonmc:messagingsystem:2836a1f6f3")
    implementation("ch.qos.logback:logback:0.5")
    implementation("ch.qos.logback:logback-classic:1.2.11")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "16"
}

application {
    mainClass.set("com.bluedragonmc.puffin.MainKt")
}
