plugins {
    alias(libs.plugins.aiopicUiFeature)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":engine:question"))
            implementation(project(":engine:answer"))
            implementation(project(":libs:tts"))
            implementation(project(":libs:recorder"))
            implementation(project(":libs:timer"))
            // 질문 줄 아래 코치 시작 버튼(F10.1)을 기록 탭과 같이 쓴다.
            implementation(project(":ui:feature-live"))
            // 나가면 잃는 것이 있을 때 시스템 뒤로가기를 받아 먼저 묻는다.
            implementation(libs.compose.uiBackhandler)
        }
        androidMain.dependencies {
            // 마이크 권한 요청 창을 띄운다.
            implementation(libs.androidx.activity.compose)
        }
        // 처리 중인 버튼이 눌리지 않는지 화면을 띄워 누르는 테스트로 본다.
        getByName("androidHostTest").dependencies {
            implementation(libs.compose.uiTestJunit4)
            implementation(libs.compose.uiTestManifest)
            // 목소리 띠가 계속 도는 녹음 중 화면은 시계를 멈춘 테스트 규칙에서 노드째 찍고, 나가기 확인 대화상자는 따로 뜨는 창이라 화면 전체를 찍는다.
            implementation(libs.roborazzi.core)
        }
    }
}
