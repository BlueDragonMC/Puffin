plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "com.bluedragonmc"
version = "1.0.0"

repositories {
    mavenCentral()
    mavenLocal()
    maven(url = "https://reposilite.bluedragonmc.com/releases")
}

dependencies {
    testImplementation(kotlin("test"))

    implementation(libs.logback)
    implementation(libs.logback.classic)

    implementation(libs.kubernetes.client.java)

    implementation(libs.mongodb.kotlin.coroutine)
    implementation(libs.bson.kotlinx)

    implementation(variantOf(libs.guice) { classifier("classes") })
    implementation(libs.asm)
    implementation(libs.caffeine)

    implementation(libs.serialization.json)

    implementation(libs.rpc)
//    implementation("com.bluedragonmc:rpc:dev")
    implementation(libs.json.patch)

    implementation(libs.grpc.services)
    implementation(libs.grpc.netty)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.protobuf.kotlin)

    implementation(libs.java.websocket)

    implementation(libs.okhttp)

    implementation(libs.configurate.yaml)

    implementation(libs.coroutines.slf4j)
}

tasks.test {
    useJUnitPlatform()
}

val javaVersion = libs.versions.jvm.get().toInt()

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(javaVersion))
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(javaVersion))
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


val installThinDistLibs = tasks.register<Sync>("installThinDistLibs") {
    dependsOn(configurations.named("runtimeClasspath"))
    into(layout.buildDirectory.dir("thin-dist/lib"))
    from(configurations.named("runtimeClasspath")) {
        include("*.jar")
    }
}

val installThinDist = tasks.register<Copy>("installThinDist") {
    dependsOn(tasks.jar, installThinDistLibs)
    into(layout.buildDirectory.dir("thin-dist"))
    from(tasks.jar)
}
