plugins {
    alias(libs.plugins.aiopicUiFeature)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":engine:session"))
            implementation(project(":engine:question"))
            implementation(project(":engine:answer"))
            implementation(project(":libs:tts"))
            implementation(project(":libs:recorder"))
            implementation(project(":libs:timer"))
            // 나가면 잃는 것이 있을 때 시스템 뒤로가기를 받아 먼저 묻는다.
            implementation(libs.compose.uiBackhandler)
        }
        // 처리 중인 버튼이 눌리지 않는지 화면을 띄워 누르는 테스트로 본다.
        getByName("androidHostTest").dependencies {
            implementation(libs.compose.uiTestJunit4)
            implementation(libs.compose.uiTestManifest)
            // 기다림 표시가 계속 도는 화면은 시계를 멈춘 테스트 규칙에서 노드째 찍는다.
            implementation(libs.roborazzi.core)
        }
    }
}
