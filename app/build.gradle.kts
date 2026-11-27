import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.jetbrains.kotlin.kapt)
}

// Optional local build configuration (local.properties is ignored by Git).
val pemesaLocal = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun pemesaSetting(name: String): String =
    providers.environmentVariable(name).orNull ?: pemesaLocal.getProperty(name, "")
fun quotedBuildValue(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\r", "\\r").replace("\n", "\\n") + "\""

android {
    namespace = "com.bharathvishal.messagecommunicationusingwearabledatalayer"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.bharathvishal.messagecommunicationusingwearabledatalayer"
        vectorDrawables {
            useSupportLibrary = true
        }
        minSdk = 23
        targetSdk = 36
        versionCode = 119
        versionName = "3.9.2"
        buildConfigField("String", "PEMESA_INFLUX_URL", quotedBuildValue(pemesaSetting("PEMESA_INFLUX_URL").trim()))
        buildConfigField("String", "PEMESA_INFLUX_ORG", quotedBuildValue(pemesaSetting("PEMESA_INFLUX_ORG").trim()))
        buildConfigField("String", "PEMESA_INFLUX_TOKEN", quotedBuildValue(pemesaSetting("PEMESA_INFLUX_TOKEN").trim()))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
        compose = true
    }
    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    packaging {
        jniLibs {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation ("androidx.compose.material:material-icons-extended:1.4.3")
    implementation ("androidx.compose.material:material:1.4.3")

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.legacy.legacysupport)
    implementation(libs.jetbrains.kotlinx.couroutine)
    implementation(libs.coil.kt)
    implementation(libs.jetbrains.kotlin.stdlib)
    implementation(libs.google.android.material)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    implementation(libs.glide)
    annotationProcessor(libs.glide.compiler)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.windowsize)
    implementation(libs.androidx.compose.material3.windowsize)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.core.splashscreen)
    implementation(libs.androidx.compose.material.material.icons)
    implementation(libs.google.accompanist.accompanist)
    implementation(libs.androidx.biometric)
    implementation(libs.playservices.wearable)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.core.ktx)
}





