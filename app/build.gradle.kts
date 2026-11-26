import java.util.Properties

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
        // Ad ogni versione pubblicata su GitHub: versionCode +1 (deve crescere per installare
        // l'aggiornamento sopra la versione precedente) e versionName aggiornato.
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Solo processori arm64 (praticamente tutti i telefoni con Android 14 e successivi): le
        // librerie native della mappa per x86, x86_64 e armeabi-v7a pesavano ~36 MB in più.
        // Niente emulatori x86 (non servono).
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // Chiave di rilascio per le versioni pubblicate su GitHub: letta da keystore.properties (nella
    // radice del progetto, esclusa da git). Senza quel file la variante "publish" non si firma.
    val keystoreFile = rootProject.file("keystore.properties")
    val releaseKey = if (keystoreFile.exists()) {
        val props = Properties()
        keystoreFile.inputStream().use { props.load(it) }
        signingConfigs.create("releaseKey") {
            storeFile = file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    } else null

    buildTypes {
        release {
            // Firmata con la chiave di debug per poterla installare sul telefono di prova sopra
            // la debug (stessa firma: preferiti e impostazioni restano). Per GitHub si usa la
            // variante "publish" qui sotto, firmata con la chiave di rilascio.
            signingConfig = signingConfigs.getByName("debug")
            optimization {
                enable = true
                // Su tutta l'app: limitarla ad alcuni pacchetti (packageScope) rendeva inaccessibili
                // classi di Kotlin usate anche fuori da quei pacchetti (IllegalAccessError all'avvio).
                // Regole di conservazione in src/main/keepRules/.
            }
        }
        // Versione da pubblicare su GitHub: identica alla release, ma firmata con la chiave di
        // rilascio. ./gradlew :app:assemblePublish → app/build/outputs/apk/publish/
        // (sul telefono di prova resta la release firmata con la chiave di debug).
        create("publish") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            signingConfig = releaseKey
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