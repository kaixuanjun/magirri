import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}


/**
 * 把当前 git 提交号编进 BuildConfig，供「关于 → 检查更新」比对。
 *
 * 优先用 -PgitCommit=xxx 传进来的值（构建脚本自己算好更可靠）；
 * 否则从工程目录往上找 .git 再问 git 要。
 */
fun gitShortCommit(): String {
    val given = (project.findProperty("gitCommit") as String?)?.trim()
    if (!given.isNullOrEmpty()) return given
    var dir: File? = project.projectDir
    while (dir != null && !File(dir, ".git").exists()) dir = dir.parentFile
    if (dir == null) return "unknown"
    return try {
        val p = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .directory(dir).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().trim().ifEmpty { "unknown" }
    } catch (e: Exception) {
        "unknown"
    }
}

android {
    namespace = "com.magirri.lume"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.magirri.lume"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "2.0.3"
        buildConfigField("String", "GIT_COMMIT", "\"" + gitShortCommit() + "\"")
        // 构建时刻（秒），用来判断"远端提交比本机新"还是"本机比远端新"
        buildConfigField("long", "BUILD_TIME", (System.currentTimeMillis() / 1000).toString() + "L")
    }

    buildFeatures {
        buildConfig = true
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

    // .cube 是文本，按 assets 原样读
    androidResources {
        noCompress += listOf("cube")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    jvmArgs("-Dfile.encoding=UTF-8")
}
