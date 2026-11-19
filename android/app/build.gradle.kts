import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

val localConfig = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun config(name: String) = providers.gradleProperty(name).orNull ?: localConfig.getProperty(name, "")
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
val mapsKey = config("MAPS_API_KEY").trim()
val apiBase = config("NAV_API_BASE_URL").trim().trimEnd('/')

android {
    namespace = "com.tobenamed.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tobenamed.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 16
        versionName = "0.8.5"
        manifestPlaceholders["MAPS_API_KEY"] = mapsKey.ifEmpty { "NOT_CONFIGURED" }
        buildConfigField("boolean", "MAPS_CONFIGURED", mapsKey.isNotEmpty().toString())
        buildConfigField("boolean", "WEB_3D_SCENE", (config("WEB_3D_SCENE") != "false").toString())
        buildConfigField("String", "NAV_API_BASE_URL", quoted(apiBase))
        buildConfigField("String", "NAV_API_TOKEN", quoted(config("NAV_API_TOKEN")))
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core-nav"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.play.maps)
    implementation(libs.maplibre)
    implementation(libs.play.location)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    // Maps' transitive Fragment 1.1 conflicts with Android's ActivityResult permission APIs.
    implementation(libs.fragment)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.mockwebserver)
    testImplementation(libs.json.test)
    testImplementation(libs.coroutines.test)
}
