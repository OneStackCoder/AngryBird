plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.redbird.angrybirds"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.redbird.angrybirds"
        // Android 11
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 使用 debug 签名，保证 CI 产出的 Release APK 可直接安装
            signingConfig = signingConfigs.getByName("debug")
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
}

dependencies {
    // 2D 刚体物理引擎（怒鸟同款物理方案）
    implementation("org.jbox2d:jbox2d-library:2.2.1.1")
}
