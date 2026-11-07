plugins {
    alias(libs.plugins.aiopicKmpLibraryExplicit)
}

kotlin {
    // tools/eval 이 실제 Live 서버로 코치 지시를 재려고 JVM 판을 쓴다.
    jvm()
    sourceSets {
        commonMain.dependencies {
            // 도구 선언과 도구 호출 인자를 JsonObject 로 주고받아서 api 로 둔다.
            api(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
        }
        androidMain.dependencies {
            // ktor-client-android 엔진은 WebSocket 을 못 열어서 OkHttp 엔진을 쓴다.
            implementation(libs.ktor.client.okhttp)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}
