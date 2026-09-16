plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.survivorno1.smsbridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.survivorno1.smsbridge"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // 固定签名用的 keystore 提交在仓库里（只是为了每次 CI 出的包签名一致、能覆盖安装，
    // 不是什么机密——这个 app 只有你自己装）
    signingConfigs {
        create("fixed") {
            storeFile = file("release.jks")
            storePassword = "smsbridge"
            keyAlias = "smsbridge"
            keyPassword = "smsbridge"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("fixed")
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

// 零第三方依赖：只用 Android SDK 自带的 API
dependencies {}
