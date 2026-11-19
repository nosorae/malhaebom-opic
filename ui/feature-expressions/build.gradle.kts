plugins {
    alias(libs.plugins.aiopicUiFeature)
}

kotlin {
    sourceSets.commonMain.dependencies {
        // 카드, FSRS 평가, 오늘 복습 고르기, 복습한 날이 엔진에 있다.
        implementation(project(":engine:review"))
    }
    sourceSets.getByName("androidHostTest").dependencies {
        implementation(libs.compose.uiTestJunit4)
        implementation(libs.compose.uiTestManifest)
        implementation(libs.roborazzi.core)
    }
}

// ViewModel 테스트가 실제 카드 저장소(메모리 Room)를 쓴다. 호스트 JVM 테스트에는 Android 용 SQLite 네이티브 라이브러리가 없어서 JVM 판으로 바꿔 돌린다.
configurations.matching { it.name.contains("HostTest") && it.name.endsWith("RuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("androidx.sqlite:sqlite-bundled")).using(module(libs.sqlite.bundled.jvm.get().toString()))
    }
}
