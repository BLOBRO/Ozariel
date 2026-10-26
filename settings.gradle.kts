pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    // Versions des plugins, appliqués dans chaque module. Le plugin Android et le plugin Kotlin
    // doivent être chargés ensemble dans le module app.
    plugins {
        id("com.android.application") version "8.10.1"
        kotlin("jvm") version "2.1.21"
        kotlin("android") version "2.1.21"
        kotlin("plugin.compose") version "2.1.21"
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "Ozariel"

// Le module « core » (cerveau de l'agent, apprentissage du style) est du Kotlin pur :
// il se compile et se teste partout. Le module « app » a besoin du SDK Android,
// il n'est inclus que si ce SDK est présent (Android Studio, GitHub Actions).
include(":core")
val androidSdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
if (androidSdk != null || file("local.properties").exists()) {
    include(":app")
}
