plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}
android {
    namespace = "org.jdownloader.remote"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "org.jdownloader.remote"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "1.0.0"
        val myjdKey = providers.environmentVariable("MYJD_APP_KEY").orElse("org.jdownloader.remote").get()
        buildConfigField("String", "MYJD_APP_KEY", "\"" + myjdKey.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
    }
    val store = System.getenv("SIGNING_STORE_FILE")
    signingConfigs { if (!store.isNullOrBlank()) create("release") {
        storeFile = file(store); storePassword = System.getenv("SIGNING_STORE_PASSWORD")
        keyAlias = System.getenv("SIGNING_KEY_ALIAS"); keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
    } }
    buildTypes { release { isMinifyEnabled = false; if (!store.isNullOrBlank()) signingConfig = signingConfigs.getByName("release") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties") }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation(libs.ktor.client); implementation(libs.ktor.server)
    implementation(libs.hilt.android); ksp(libs.hilt.compiler)
    implementation("androidx.webkit:webkit:1.12.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.ktor:ktor-client-mock:2.3.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
