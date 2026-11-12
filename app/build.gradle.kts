import org.gradle.api.GradleException
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("kotlin-android")
}

android {
    namespace = "com.pulsarnav.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pulsarnav.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 16
        versionName = "1.2.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val envStoreFile = System.getenv("RELEASE_STORE_FILE")?.takeIf { it.isNotBlank() }
            val envStorePassword = System.getenv("RELEASE_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
            val envKeyAlias = System.getenv("RELEASE_KEY_ALIAS")?.takeIf { it.isNotBlank() }
            val envKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")?.takeIf { it.isNotBlank() }

            val keystorePropsFile = rootProject.file("keystore.properties")
            val keystoreProps = Properties().apply {
                if (keystorePropsFile.exists()) {
                    FileInputStream(keystorePropsFile).use { load(it) }
                }
            }

            if (envStoreFile != null) {
                if (envStorePassword == null || envKeyAlias == null || envKeyPassword == null) {
                    throw GradleException(
                        "RELEASE_STORE_FILE is set without full RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, and RELEASE_KEY_PASSWORD environment variables."
                    )
                }
                val targetStore = file(envStoreFile)
                if (!targetStore.exists()) {
                    throw GradleException("RELEASE_STORE_FILE does not exist: ${targetStore.absolutePath}")
                }
                storeFile = targetStore
                storePassword = envStorePassword
                keyAlias = envKeyAlias
                keyPassword = envKeyPassword
            } else if (keystorePropsFile.exists()) {
                val propStoreFile = keystoreProps.getProperty("storeFile")?.takeIf { it.isNotBlank() }
                    ?: throw GradleException("keystore.properties is missing storeFile")
                val propStorePassword = keystoreProps.getProperty("storePassword")?.takeIf { it.isNotBlank() }
                    ?: throw GradleException("keystore.properties is missing storePassword")
                val propKeyAlias = keystoreProps.getProperty("keyAlias")?.takeIf { it.isNotBlank() }
                    ?: throw GradleException("keystore.properties is missing keyAlias")
                val propKeyPassword = keystoreProps.getProperty("keyPassword")?.takeIf { it.isNotBlank() }
                    ?: throw GradleException("keystore.properties is missing keyPassword")
                val targetStore = file(propStoreFile)
                if (!targetStore.exists()) {
                    throw GradleException("keystore.properties storeFile does not exist: ${targetStore.absolutePath}")
                }
                storeFile = targetStore
                storePassword = propStorePassword
                keyAlias = propKeyAlias
                keyPassword = propKeyPassword
            } else {
                val requirePrivateSigning =
                    (project.findProperty("requirePrivateReleaseSigning")?.toString()?.toBooleanStrictOrNull() == true) ||
                        (System.getenv("REQUIRE_PRIVATE_RELEASE_SIGNING")?.equals("true", ignoreCase = true) == true)
                if (requirePrivateSigning) {
                    throw GradleException(
                        "Private release signing is required (requirePrivateReleaseSigning / REQUIRE_PRIVATE_RELEASE_SIGNING), " +
                            "but neither RELEASE_STORE_FILE nor keystore.properties is configured."
                    )
                }
                val ksFile = file("keystore/pulsarnav-release.jks")
                if (ksFile.exists()) {
                    logger.warn(
                        "WARNING: Signing release build with shared fallback keystore app/keystore/pulsarnav-release.jks. " +
                            "Configure keystore.properties or RELEASE_* secrets before distributing private releases."
                    )
                    storeFile = ksFile
                    storePassword = envStorePassword ?: "pulsarnav"
                    keyAlias = envKeyAlias ?: "pulsarnav"
                    keyPassword = envKeyPassword ?: "pulsarnav"
                } else {
                    initWith(signingConfigs.getByName("debug"))
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
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

    buildFeatures {
        viewBinding = true
        buildConfig = true
        aidl = false
        renderScript = false
        resValues = false
        shaders = false
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // QR Code Generation & Scanning for Data Vault Migration
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
