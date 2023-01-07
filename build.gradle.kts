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

val grpcKotlinVersion = "1.3.0"
val protoVersion = "3.21.9"
val grpcVersion = "1.51.0"

dependencies {
    testImplementation(kotlin("test"))

    implementation("ch.qos.logback:logback:0.5")
    implementation("ch.qos.logback:logback-classic:1.4.5")

    implementation("io.kubernetes:client-java:17.0.0")

    implementation("org.litote.kmongo:kmongo:4.8.0")
    implementation("org.litote.kmongo:kmongo-coroutine:4.8.0")

    implementation("com.github.ben-manes.caffeine:caffeine:3.1.2")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.4.1")

    implementation("com.github.bluedragonmc:rpc:3c2e12a1d8")

    implementation("io.grpc:grpc-services:$grpcVersion")
    implementation("io.grpc:grpc-netty:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("io.grpc:grpc-kotlin-stub:$grpcKotlinVersion")
    implementation("com.google.protobuf:protobuf-kotlin:$protoVersion")

    implementation("org.java-websocket:Java-WebSocket:1.5.3")

    implementation("com.squareup.okhttp3:okhttp:4.10.0")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "16"
}

tasks.shadowJar {
    mergeServiceFiles()
}

application {
    mainClass.set("com.bluedragonmc.puffin.app.MainKt")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.bluedragonmc.puffin.app.MainKt"
    }
}