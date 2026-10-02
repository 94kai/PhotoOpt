import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val reusableDebugSigning = Properties().apply {
    val propertiesFile = rootProject.file("signing/photoopt-debug.properties")
    if (propertiesFile.isFile) propertiesFile.inputStream().use(::load)
}
val localProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.isFile) propertiesFile.inputStream().use(::load)
}
val amapApiKey = providers.gradleProperty("AMAP_API_KEY").orNull
    ?: System.getenv("AMAP_API_KEY")
    ?: localProperties.getProperty("AMAP_API_KEY")
    ?: ""

android {
    namespace = "com.xk.photoopt"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xk.photoopt"
        minSdk = 31
        targetSdk = 35
        versionCode = 53
        versionName = "1.10.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["AMAP_API_KEY"] = amapApiKey
        buildConfigField("String", "AMAP_API_KEY", "\"$amapApiKey\"")
    }

    signingConfigs.getByName("debug").apply {
        reusableDebugSigning.getProperty("storeFile")?.let { storeFile = rootProject.file(it) }
        reusableDebugSigning.getProperty("storePassword")?.let { storePassword = it }
        reusableDebugSigning.getProperty("keyAlias")?.let { keyAlias = it }
        reusableDebugSigning.getProperty("keyPassword")?.let { keyPassword = it }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.1")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.media3:media3-transformer:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")
    implementation("androidx.media3:media3-muxer:1.5.1")
    implementation("com.amap.api:3dmap-location-search:10.1.200_loc6.4.9_sea9.7.4")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
