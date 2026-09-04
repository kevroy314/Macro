import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.macropad.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.macropad.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 46
        versionName = "2.9.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Dropbox App Key
        buildConfigField("String", "DROPBOX_APP_KEY", "\"aulgixn4nqiw12b\"")
    }

    // Release signing credentials live in keystore.properties, which is git-ignored.
    // They used to be written here in plain text, in a file tracked in a public
    // repository — the password that was published there has since been changed.
    // Anyone holding the keystore can sign an update this app installs as genuine,
    // so neither the file nor its password belongs in version control.
    //
    // Copy keystore.properties.example to keystore.properties to build a release.
    val keystoreProperties = Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

    signingConfigs {
        create("release") {
            val store = keystoreProperties.getProperty("storeFile")
                ?: "../macropad-release-key.jks"
            storeFile = file(store)
            storePassword = keystoreProperties.getProperty("storePassword")
                ?: System.getenv("MACROPAD_STORE_PASSWORD")
            keyAlias = keystoreProperties.getProperty("keyAlias") ?: "macropad"
            keyPassword = keystoreProperties.getProperty("keyPassword")
                ?: System.getenv("MACROPAD_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.1")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.5")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Glance (Widgets)
    implementation("androidx.glance:glance-appwidget:1.0.0")
    implementation("androidx.glance:glance-material3:1.0.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // Dropbox SDK
    implementation("com.dropbox.core:dropbox-core-sdk:5.4.5")

    // OkHttp for network requests (proper SSL handling)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Image loading for AI entry thumbnails
    implementation("io.coil-kt:coil-compose:2.5.0")

    // Reliable EXIF orientation for camera photos
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // QR codes: zxing encodes, and Play Services scans in its own UI so the app
    // never needs the CAMERA permission (which would break ACTION_IMAGE_CAPTURE).
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    // WorkManager for background sync
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Unit tests for pure logic (search scoring, macro arithmetic)
    testImplementation("junit:junit:4.13.2")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
