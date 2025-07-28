import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.serialization") version "2.1.10"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
}

group = "com.bluedragonmc"
version = "1.0.0"

repositories {
    mavenCentral()
    mavenLocal()
    maven(url = "https://jitpack.io")
}

val grpcKotlinVersion = "1.4.1"
val protoVersion = "4.30.1"
val grpcVersion = "1.71.0"

dependencies {
    testImplementation(kotlin("test"))

    implementation("ch.qos.logback:logback:0.5")
    implementation("ch.qos.logback:logback-classic:1.5.17")

    implementation("io.kubernetes:client-java:23.0.0")

    implementation("org.litote.kmongo:kmongo:5.2.1")
    implementation("org.litote.kmongo:kmongo-coroutine:5.2.1")

    implementation("com.github.ben-manes.caffeine:caffeine:3.2.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    implementation("com.github.bluedragonmc:rpc:d40ac743b5")
//    implementation("com.bluedragonmc:rpc:1.0")
    implementation("com.tananaev:json-patch:1.2")

    implementation("io.grpc:grpc-services:$grpcVersion")
    implementation("io.grpc:grpc-netty:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("io.grpc:grpc-kotlin-stub:$grpcKotlinVersion")
    implementation("com.google.protobuf:protobuf-kotlin:$protoVersion")

    implementation("org.java-websocket:Java-WebSocket:1.6.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
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
