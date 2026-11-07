plugins {
    alias(libs.plugins.aiopicAndroidApplication)
}

android {
    defaultConfig {
        applicationId = "io.github.nosorae.aiopic"
        versionCode = 1
        versionName = "1.0"
        // 시나리오 테스트가 Gemini 만 저장한 응답으로 바꾼 앱으로 띄운다.
        testInstrumentationRunner = "io.github.nosorae.aiopic.apps.opic.androidapp.E2eRunner"
    }
    // 테스트마다 계측을 새로 띄워서, 앞 테스트가 끝날 때 앱 프로세스가 강제 종료된 뒤 이어 보기를 확인한다.
    // 애니메이션을 끄면 앱이 동작 줄이기(끝 장면 바로 보이기)로 돈다. 녹음 중 목소리 띠처럼 계속 도는 그림이 Compose 를 쉬지 못하게 해서
    // 테스트가 기다리다 멈추는 것을 막는다(#194).
    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        animationsDisabled = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    implementation(project(":apps:opic:shared"))
    // 기기에 묶인 객체(키 보관소, 녹음기, 기기 음성, 대기열)를 앱이 만들어 그래프에 넘긴다.
    implementation(project(":engine:answer"))
    implementation(project(":libs:keyvault"))
    implementation(project(":libs:recorder"))
    implementation(project(":libs:tts"))
    implementation(project(":libs:work-queue"))
    // 코치 대화의 말하면서 듣는 오디오를 그래프에 넘긴다.
    implementation(project(":libs:duplex-audio"))
    // 시작 분기(F1.0)가 끝날 때까지 시스템 스플래시를 잡아 두고, 그 결정을 맡은 ViewModel 을 Activity 가 만든다.
    implementation(project(":ui:feature-onboarding"))
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
    // 시나리오 테스트가 Gemini 클라이언트를 가짜 HTTP 엔진으로 바꿔 넣는다.
    implementation(project(":libs:llm-gemini"))
    // 디버깅 도구는 빈 모듈로 컴파일하고 디버그 APK 에서만 실제 모듈로 바꿔 끼운다(아래).
    implementation(project(":libs:debug-noop"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.testExt.junit)
    androidTestImplementation(libs.compose.uiTestJunit4)
    androidTestImplementation(libs.ktor.client.mock)
    // 알림창을 열고 알림을 누르는 시나리오 테스트(#229). 앱 밖 화면이라 Compose 테스트로는 닿지 않는다.
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestUtil(libs.androidx.test.orchestrator)
}

// 디버그 APK 와 그 계측 테스트에만 실제 디버깅 도구(화면, 알림 권한, 흔들기)를 싣는다. 두 모듈은 공개 API 가 같아서(checkNoopAbi)
// 빈 모듈로 컴파일한 코드가 실행할 때 실제 모듈을 그대로 부른다. 릴리스 APK 에 실제 모듈이 없는지는 apps/opic/scripts/check-release-no-debug.sh 로 본다.
configurations.matching { it.name.startsWith("debug") && it.name.endsWith("RuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(project(":libs:debug-noop")).using(project(":libs:debug"))
    }
}
