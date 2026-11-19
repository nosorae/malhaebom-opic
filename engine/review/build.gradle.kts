plugins {
    alias(libs.plugins.aiopicKmpLibrary)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // 카드와 복습한 날 표가 앱 DB(OpicDatabase) 안에 있다.
            api(project(":engine:answer"))
            // 오늘과 복습한 날은 기기 시간대의 로컬 날짜로 센다(ADR 0040).
            api(libs.kotlinx.datetime)
        }
    }
}

// 호스트 JVM 테스트에는 Android 용 SQLite 네이티브 라이브러리가 없어서, 같은 API 의 JVM 판으로 바꿔 돌린다.
configurations.matching { it.name.contains("HostTest") && it.name.endsWith("RuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("androidx.sqlite:sqlite-bundled")).using(module(libs.sqlite.bundled.jvm.get().toString()))
    }
}
