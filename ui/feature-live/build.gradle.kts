plugins {
    alias(libs.plugins.aiopicUiFeature)
}

kotlin {
    sourceSets.commonMain.dependencies {
        // 대화 화면이 코치 세션 상태를 그리고, 목소리 띠가 오디오의 목소리 크기를 읽는다.
        implementation(project(":engine:coach"))
        // 리허설은 Live 대신 기존 녹음기로 받는다(F13.1).
        implementation(project(":libs:recorder"))
        implementation(project(":libs:timer"))
    }
    // 마이크 권한 요청 창을 띄운다.
    sourceSets.androidMain.dependencies {
        implementation(libs.androidx.activity.compose)
    }
    // 시작 시트는 따로 뜨는 창이라 화면 전체를 찍고, 목소리 띠가 계속 도는 대화 화면은 시계를 멈춘 테스트 규칙에서 찍는다.
    sourceSets.getByName("androidHostTest").dependencies {
        implementation(libs.compose.uiTestJunit4)
        implementation(libs.compose.uiTestManifest)
        implementation(libs.roborazzi.core)
    }
}

// ViewModel 테스트가 실제 코치 저장소(메모리 Room)를 쓴다. 호스트 JVM 테스트에는 Android 용 SQLite 네이티브 라이브러리가 없어서 JVM 판으로 바꿔 돌린다.
configurations.matching { it.name.contains("HostTest") && it.name.endsWith("RuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("androidx.sqlite:sqlite-bundled")).using(module(libs.sqlite.bundled.jvm.get().toString()))
    }
}
