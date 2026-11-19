plugins {
    alias(libs.plugins.aiopicKmpLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // 말로 복습과 몰랐던 표현 카드를 카드 저장소에 쓰고, 연습 표는 그 아래 앱 DB(OpicDatabase) 에 있다.
            api(project(":engine:review"))
            // 라이브 코치와 같이 쓰는 것은 연결과 오디오 라이브러리뿐이다(ADR 0039).
            api(project(":libs:live-gemini"))
            api(project(":libs:duplex-audio"))
        }
    }
}

// 호스트 JVM 테스트에는 Android 용 SQLite 네이티브 라이브러리가 없어서, 같은 API 의 JVM 판으로 바꿔 돌린다.
configurations.matching { it.name.contains("HostTest") && it.name.endsWith("RuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("androidx.sqlite:sqlite-bundled")).using(module(libs.sqlite.bundled.jvm.get().toString()))
    }
}
