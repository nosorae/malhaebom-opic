plugins {
    alias(libs.plugins.aiopicUiFeature)
}

kotlin {
    sourceSets.commonMain.dependencies {
        // 연습 상태와 연습 기록은 말하기 엔진, 카드와 복습 계산은 복습 엔진에 있다.
        implementation(project(":engine:speaking"))
        implementation(project(":engine:review"))
        // 실시간 음성 전송 동의(F15.1)는 라이브 코치와 같은 값이고, 카드 꼬리표는 표현 탭과 같이 그린다.
        implementation(project(":ui:feature-live"))
        implementation(project(":ui:feature-expressions"))
    }
    // 시작 시트는 따로 뜨는 창이라 화면 전체를 찍고, 목소리 띠가 도는 연습 중 화면은 동작 줄이기로 멈춘 장면을 찍는다.
    sourceSets.getByName("androidHostTest").dependencies {
        implementation(libs.compose.uiTestJunit4)
        implementation(libs.compose.uiTestManifest)
        implementation(libs.roborazzi.core)
    }
}

// ViewModel 테스트가 실제 연습 기록과 카드 저장소(메모리 Room)를 쓴다. 호스트 JVM 테스트에는 Android 용 SQLite 네이티브 라이브러리가 없어서 JVM 판으로 바꿔 돌린다.
configurations.matching { it.name.contains("HostTest") && it.name.endsWith("RuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("androidx.sqlite:sqlite-bundled")).using(module(libs.sqlite.bundled.jvm.get().toString()))
    }
}
