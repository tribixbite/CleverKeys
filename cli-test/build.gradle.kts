plugins {
    kotlin("jvm") version "2.0.21"
    application
}

group = "tribixbite.cleverkeys"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
}

application {
    mainClass.set("TestOnnxCliKt")
}

tasks.register("runTest", JavaExec::class) {
    group = "verification"
    description = "Run complete ONNX CLI test"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("TestOnnxCliKt")
}
