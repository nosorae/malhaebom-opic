rootProject.name = "AiOpicSelfStudy"

pluginManagement {
    includeBuild("build-logic")
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
    }
}

include(
    ":apps:opic:shared",
    ":apps:opic:androidApp",
    ":ui:design",
    ":ui:navigation",
    ":ui:feature-onboarding",
    ":ui:feature-home",
    ":ui:feature-exam",
    ":ui:feature-practice",
    ":ui:feature-report",
    ":ui:feature-history",
    ":ui:feature-settings",
    ":ui:feature-live",
    ":packs:opic-en",
    ":engine:session",
    ":engine:answer",
    ":engine:question",
    ":engine:coach",
    ":engine:ai-gemini",
    ":libs:recorder",
    ":libs:duplex-audio",
    ":libs:llm-gemini",
    ":libs:live-gemini",
    ":libs:work-queue",
    ":libs:keyvault",
    ":libs:tts",
    ":libs:timer",
    ":libs:haptics",
    ":libs:debug",
    ":libs:debug-noop",
    ":tools:eval",
)
