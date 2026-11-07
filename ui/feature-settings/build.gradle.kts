plugins {
    alias(libs.plugins.aiopicUiFeature)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // 검증된 모델 목록(ModelCatalog)과 기본 모델로 돌리는 규칙(choose)을 쓴다.
            // 틀림 표시 내보내기도 답변과 표시 타입을 그대로 읽는다(F8.3).
            implementation(project(":engine:answer"))
            // 불러온 문항 파일을 내장 문항 풀과 같은 규칙으로 검사한다(F6.3).
            implementation(project(":engine:question"))
            // 키를 넣을 수 있는 제공사 목록(KeyProvider)과 음성 전송 동의를 온보딩 화면과 같이 쓴다.
            implementation(project(":ui:feature-onboarding"))
            implementation(project(":libs:keyvault"))
            // 코치 목표 등급(F10.3)을 시작 시트와 같은 저장소, 같은 칩으로 고른다.
            implementation(project(":ui:feature-live"))
        }
        // 복사 완료 체크가 동작 줄이기에서 멈춰 있는지 화면을 띄워 프레임을 비교한다.
        getByName("androidHostTest").dependencies {
            implementation(libs.compose.uiTestJunit4)
            implementation(libs.compose.uiTestManifest)
        }
        androidMain.dependencies {
            // 문항 파일을 고르는 시스템 창을 띄운다.
            implementation(libs.androidx.activity.compose)
        }
    }
}
