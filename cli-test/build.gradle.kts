plugins {
    kotlin("jvm") version "1.9.0"
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
    mainClass.set("TestOnnxCli")
}

tasks.register("runTest", JavaExec::class) {
    group = "verification"
    description = "Run complete ONNX CLI test"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("TestOnnxCli")
}
