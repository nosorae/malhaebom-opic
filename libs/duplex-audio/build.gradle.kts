plugins {
    alias(libs.plugins.aiopicKmpLibraryExplicit)
}

kotlin {
    sourceSets {
        // 마이크 조각과 음량을 Flow 로 낸다.
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
    }
}
