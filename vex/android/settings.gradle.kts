pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 液态玻璃底栏：AndroidLiquidGlassView（JitPack 发布）
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "magirri"
include(":app")
