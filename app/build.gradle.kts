plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "com.wifimaxxer"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.wifimaxxer"
        minSdk = 29
        targetSdk = 37
        versionCode = 3
        versionName = "0.2.1"
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3:1.5.0-alpha27")
    testImplementation("junit:junit:4.13.2")
}
