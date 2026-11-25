rootProject.name = "Nuvio"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // YouTube engine (MediaServiceCore) for Music: Cronet lives on Google's maven under org.chromium
        google {
            mavenContent { includeGroupAndSubgroups("org.chromium") }
        }
        // ... and a couple of its libraries come from JitPack
        maven("https://jitpack.io") {
            mavenContent { includeGroupAndSubgroups("com.github") }
        }
    }
}

include(":composeApp")
include(":androidApp")
include(":baselineprofile")

// SmartTube's YouTube engine (MediaServiceCore, MIT), used by Music (from Nuvio One M, GPL-3.0).
include(":mediaservicecore:mediaserviceinterfaces")
include(":mediaservicecore:youtubeapi")
include(":mediaservicecore:sharedutils")
include(":mediaservicecore:commons-io")
include(":mediaservicecore:j2v8")
