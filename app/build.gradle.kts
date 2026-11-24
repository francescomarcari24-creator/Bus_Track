plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.bustrack"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.bustrack"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Firmata con la chiave di debug per poterla installare sul telefono di prova sopra
            // la debug (stessa firma: preferiti e impostazioni restano). Da sostituire con una
            // chiave di rilascio vera prima di pubblicare l'app.
            signingConfig = signingConfigs.getByName("debug")
            optimization {
                enable = true
                // Su tutta l'app: limitarla ad alcuni pacchetti (packageScope) rendeva inaccessibili
                // classi di Kotlin usate anche fuori da quei pacchetti (IllegalAccessError all'avvio).
                // Regole di conservazione in src/main/keepRules/.
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.gtfs.realtime.bindings)
    implementation(libs.maplibre.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

tasks.matching { it.name.contains("AarMetadata") }.configureEach {
    enabled = false
}