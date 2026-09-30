plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "th.bms.bpgateway"
    compileSdk = 34

    defaultConfig {
        applicationId = "th.bms.bpgateway"
        minSdk = 26          // Android 8.0 ขึ้นไป
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // ใช้ debug key เซ็นชั่วคราว เพื่อให้ได้ APK ที่ติดตั้งได้ทันที
            // ถ้าจะแจกจ่ายจริง ให้สร้าง keystore ของตัวเองแทน (Build > Generate Signed APK)
            signingConfig = signingConfigs.getByName("debug")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    testImplementation("junit:junit:4.13.2")
}
