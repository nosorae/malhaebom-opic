plugins {
    alias(libs.plugins.aiopicKmpLibrary)
    alias(libs.plugins.kotlinSerialization)
}

// 엔진의 분석 포트(engine/answer)와 코치 연결 포트(engine/coach)를 libs 의 Gemini 클라이언트로 구현하는 어댑터 모듈이다.
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":engine:answer"))
            implementation(project(":engine:coach"))
            implementation(project(":libs:llm-gemini"))
            implementation(project(":libs:live-gemini"))
        }
        commonTest.dependencies {
            // 저장해 둔 실제 응답을 MockEngine 으로 돌려준다.
            implementation(libs.ktor.client.mock)
        }
    }
}
