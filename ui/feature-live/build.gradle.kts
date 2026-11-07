plugins {
    alias(libs.plugins.aiopicUiFeature)
}

kotlin {
    // 시작 시트는 따로 뜨는 창이라 화면 전체를 찍는다.
    sourceSets.getByName("androidHostTest").dependencies {
        implementation(libs.compose.uiTestJunit4)
        implementation(libs.compose.uiTestManifest)
        implementation(libs.roborazzi.core)
    }
}
