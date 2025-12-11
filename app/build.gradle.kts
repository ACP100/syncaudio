plugins {
    id("com.android.application")
    // Keep: Applies the necessary Kotlin Android base plugin.
    kotlin("android")

    // REMOVED: kotlin("plugin.compose")
    // The Compose compiler plugin is handled by 'buildFeatures { compose = true }'
    // and the AGP itself, preventing the "Plugin was not found" error.
}

android {
    namespace = "com.example.syncaudio"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.syncaudio"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        // ESSENTIAL: This is the correct flag to enable Compose support.
        compose = true
    }

    composeOptions {
        // IMPORTANT: The version '1.5.11' is critical and must match
        // the Kotlin version in your root file (1.9.22) and the Compose BOM (2024.02.00).
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    packagingOptions {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        }
    }
}
dependencies {
    // Core dependencies
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Compose dependencies
    implementation("androidx.activity:activity-compose:1.8.2")

    // Uses the latest stable versions defined in one place
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.android.material:material:1.11.0") // Or a newer version
    // Recommended Compose Test Dependencies (added for completeness)
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    // Debugging tools for Compose
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}