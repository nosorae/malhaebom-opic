plugins {
    alias(libs.plugins.aiopicKmpCompose)
    alias(libs.plugins.metro)
    // Android 앱 틀이 백스택을 화면 회전과 프로세스 종료 뒤에도 되살리려고 경로를 JSON 으로 저장한다.
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    sourceSets.commonMain.dependencies {
        implementation(project(":ui:design"))
        implementation(project(":ui:feature-onboarding"))
        implementation(project(":ui:feature-home"))
        implementation(project(":ui:feature-settings"))
        // 화면 저장소 인터페이스를 Room 저장소(OpicStore) 위에 구현한다(data/).
        implementation(project(":ui:feature-exam"))
        implementation(project(":ui:feature-practice"))
        implementation(project(":ui:feature-report"))
        implementation(project(":ui:feature-history"))
        // 코치 시작 시트를 연습 탭과 기록 탭 위에 띄우고, 목표 등급 저장소를 설정 저장소로 구현한다(F10).
        implementation(project(":ui:feature-live"))
        // 설정의 모델 목록으로 앱이 들고 있는 Gemini 목록을 넣는다.
        implementation(project(":engine:answer"))
        implementation(project(":engine:ai-gemini"))
        // 코치 대화 화면에 코치(엔진, Gemini Live 연결, 말하면서 듣는 오디오)를 조립해 넘긴다(#265).
        implementation(project(":engine:coach"))
        implementation(project(":libs:duplex-audio"))
        implementation(project(":libs:keyvault"))
        // 그래프가 플랫폼 인자(녹음기, 기기 음성)를 받고 Gemini 클라이언트, 시험 팩, 시계를 만든다.
        implementation(project(":libs:recorder"))
        implementation(project(":libs:tts"))
        implementation(project(":libs:timer"))
        // 플랫폼 진동에 설정의 켜기/끄기를 씌워 화면에 넘긴다.
        implementation(project(":libs:haptics"))
        implementation(project(":libs:llm-gemini"))
        // 분석 작업을 넣으면 플랫폼 대기열을 깨우고, 대기열은 분석 작업 표를 비운다(#205).
        implementation(project(":libs:work-queue"))
        implementation(project(":packs:opic-en"))
        implementation(libs.androidx.lifecycle.viewmodelCompose)
        implementation(libs.kotlinx.serialization.json)
        // 컴파일은 빈 모듈로 하고 디버그 실행 파일에서만 실제 모듈로 바꿔 끼운다(아래 iOS, androidApp 의 바꿔치기).
        implementation(project(":libs:debug-noop"))
    }
    // Android 앱 틀의 하단 탭은 Material 3, 뒤로가기는 Navigation 3 와 Activity 의 뒤로가기 처리를 쓴다. iOS 는 SwiftUI 가 그린다.
    sourceSets.androidMain.dependencies {
        implementation(libs.androidx.navigation3.ui)
        // 화면마다 ViewModel 저장소를 따로 두어, 백스택에서 빠진 화면(나간 시험 등)의 ViewModel 이 다음 진입에 다시 쓰이지 않게 한다.
        implementation(libs.androidx.lifecycle.viewmodelNavigation3)
        implementation(libs.androidx.activity.compose)
    }
    // 분석 처리기 테스트가 Gemini 응답을 가짜 HTTP 엔진으로 돌려준다.
    sourceSets.commonTest.dependencies {
        implementation(libs.ktor.client.mock)
    }
    sourceSets.getByName("androidHostTest").dependencies {
        implementation(libs.compose.uiTestJunit4)
        implementation(libs.compose.uiTestManifest)
        // 스토어 스크린샷이 데이터가 그려진 뒤 테스트 시계를 넘겨 찍는다(노드 찍기는 코어에 있다).
        implementation(libs.roborazzi.core)
    }
}

// Xcode 가 Debug 구성으로 프레임워크를 만들 때(embedAndSignAppleFrameworkForXcode 가 CONFIGURATION 을 넘긴다)만 실제 디버깅 도구를 싣는다.
// Release 구성과 Gradle 로 돌리는 iOS 테스트는 빈 모듈을 쓴다. 릴리스 프레임워크에 실제 모듈이 없는지는 apps/opic/scripts/check-release-no-debug.sh 로 본다.
val xcodeConfiguration = providers.environmentVariable("CONFIGURATION").orNull
if (xcodeConfiguration != null && xcodeConfiguration != "Release") {
    configurations.matching { it.name.startsWith("ios") }.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(project(":libs:debug-noop")).using(project(":libs:debug"))
        }
    }
}

// 호스트 JVM 테스트에는 Android 용 SQLite 네이티브 라이브러리가 없어서, 같은 API 의 JVM 판으로 바꿔 돌린다.
configurations.matching { it.name.contains("HostTest") && it.name.endsWith("RuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("androidx.sqlite:sqlite-bundled")).using(module(libs.sqlite.bundled.jvm.get().toString()))
    }
}

// 실제 Gemini 를 부르는 테스트(live/)는 요청마다 키의 한도와 요금을 쓴다. `--tests '*LiveGeminiTest*'` 로 이름을 집어 부를 때만 돌고 `./gradlew check` 에서는 빠진다.
// 부를 때는 지난 결과를 다시 쓰지 않고 매번 실제로 보낸다.
val liveRequested = gradle.startParameter.taskRequests.any { request -> request.args.any { "LiveGeminiTest" in it } }
tasks.withType<Test>().configureEach {
    if (liveRequested) outputs.upToDateWhen { false } else filter.excludeTestsMatching("*LiveGeminiTest")
}
