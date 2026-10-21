plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.magirri.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.magirri.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
        // 只保留 arm64：手机端不需要 x86
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // .cube 是文本，压缩后体积小很多；但我们要按需读取，仍走 assets
    androidResources {
        noCompress += listOf("cube")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // 液态玻璃底栏：RenderNode + AGSL 折射/色散（API 33+ 生效，低版本自动回退）
    implementation("com.qmdeve.liquidglass:core:1.1.0")
}

// 单元测试跑在独立 JVM 里，默认编码可能不是 UTF-8（中文断言消息会乱码）
tasks.withType<Test>().configureEach {
    jvmArgs("-Dfile.encoding=UTF-8")
}
// 依赖库里带了 Kotlin 2.2 的 stdlib，而本项目用 1.9 编译器 —— 强行压回同版本，
// 否则报 "kotlin.Unit was compiled with an incompatible version of Kotlin"
configurations.configureEach {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.24")
    }
}