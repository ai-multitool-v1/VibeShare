plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core"))
    api(project(":domain"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(project(":pairing"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
