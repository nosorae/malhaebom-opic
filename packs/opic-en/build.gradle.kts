plugins {
    alias(libs.plugins.aiopicKmpLibrary)
    alias(libs.plugins.kotlinSerialization)
    // 프롬프트 파일을 composeResources/files 에 두고 Res.readBytes 로 읽으려고 건다. 화면 코드는 없다.
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    // 켜지 않으면 Android 앱 APK 에 문항 풀과 프롬프트 파일이 실리지 않아 출제와 분석이 리소스 없음으로 멈춘다.
    android {
        androidResources.enable = true
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":engine:session"))
            api(project(":engine:answer"))
            api(project(":engine:coach"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.components.resources)
        }
        iosTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

compose.resources {
    packageOfResClass = "io.github.nosorae.aiopic.packs.opicen"
}
