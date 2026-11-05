plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.serialization") version "2.3.0"
    application
}

group = "com.bluedragonmc"
version = "1.0.0"

repositories {
    mavenCentral()
    mavenLocal()
    maven(url = "https://reposilite.bluedragonmc.com/releases")
}

val grpcKotlinVersion = "1.4.1"
val protoVersion = "4.30.1"
val grpcVersion = "1.71.0"
val mongodbVersion = "5.14.0"

dependencies {
    testImplementation(kotlin("test"))

    implementation("ch.qos.logback:logback:0.5")
    implementation("ch.qos.logback:logback-classic:1.5.17")

    implementation("io.kubernetes:client-java:26.0.0")

    implementation("org.mongodb:mongodb-driver-kotlin-coroutine:$mongodbVersion")
    implementation("org.mongodb:bson-kotlinx:$mongodbVersion")

    implementation("com.google.inject:guice:7.0.0:classes")
    implementation("org.ow2.asm:asm:9.9.1")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    implementation("com.bluedragonmc:rpc:2026-10-31-015108b")
//    implementation("com.bluedragonmc:rpc:dev")
    implementation("com.github.java-json-tools:json-patch:1.13")

    implementation("io.grpc:grpc-services:$grpcVersion")
    implementation("io.grpc:grpc-netty:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("io.grpc:grpc-kotlin-stub:$grpcKotlinVersion")
    implementation("com.google.protobuf:protobuf-kotlin:$protoVersion")

    implementation("org.java-websocket:Java-WebSocket:1.6.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("org.spongepowered:configurate-yaml:4.2.0")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

application {
    mainClass.set("com.bluedragonmc.puffin.app.MainKt")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.bluedragonmc.puffin.app.MainKt"
    }
}


val installThinDistLibs by tasks.registering(Sync::class) {
    dependsOn(configurations.named("runtimeClasspath"))
    into(layout.buildDirectory.dir("thin-dist/lib"))
    from(configurations.named("runtimeClasspath").map { configuration ->
        configuration.files.filter { file -> file.isFile && file.name.endsWith(".jar") }
    })
}

val installThinDist by tasks.registering(Copy::class) {
    dependsOn(tasks.jar, installThinDistLibs)
    into(layout.buildDirectory.dir("thin-dist"))
    from(tasks.jar)
}
