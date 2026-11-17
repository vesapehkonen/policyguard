plugins {
    id("com.android.application")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

android {
    namespace = "org.example.devicepolicyguard"
    compileSdk = 35

    lint {
        // This DPC is provisioned directly on an Android 12 test device, not published to Play.
        disable += "ExpiredTargetSdkVersion"
    }

    defaultConfig {
        applicationId = "org.example.devicepolicyguard"
        minSdk = 31
        targetSdk = 31
        versionCode = 19
        versionName = "1.4.12"
    }

    signingConfigs {
        create("field") {
            val path = providers.environmentVariable("DPC_KEYSTORE_FILE").orNull
            if (path != null) {
                storeFile = file(path)
                storePassword = providers.environmentVariable("DPC_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("DPC_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("DPC_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        create("enrollment") {
            // QR provisioning cannot install a testOnly APK on a fresh device.
            // Use the existing local debug key for a convenient lab enrollment build.
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
            if (providers.environmentVariable("DPC_KEYSTORE_FILE").isPresent) {
                signingConfig = signingConfigs.getByName("field")
            }
        }
    }
}
