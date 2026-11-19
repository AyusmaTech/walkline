// Pure Kotlin navigation engine.
// Must stay free of Android / Google SDK imports so it can be unit-tested on the JVM
// and, if needed later, moved to Kotlin Multiplatform for sharing with iOS.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
