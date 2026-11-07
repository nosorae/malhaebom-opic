plugins {
    alias(libs.plugins.aiopicKmpCompose)
}

// 앱 틀의 하단 탭 아이콘과 화면의 버튼 아이콘이 Montage 아이콘을 꺼내 쓴다.
compose.resources {
    publicResClass = true
}

kotlin {
    android {
        // 움직임이 실제 기기 프레임 시계에서 60fps 를 지키는지, 앱이 뒤로 가면 멈추는지는 에뮬레이터에서 본다.
        withDeviceTestBuilder {}.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }
    sourceSets {
        // 화면이 LocalHaptics 로 꺼낸 진동의 효과 종류를 직접 쓰게 공개 API 를 넘겨준다.
        commonMain.dependencies {
            api(project(":libs:haptics"))
        }
        // 모의고사, 연습, 코치 대화가 같이 쓰는 마이크 권한 요청 창을 띄운다.
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
        // 움직임 부품을 테스트 시계로 멈춰 끝 장면과 동작 줄이기를 비교한다.
        getByName("androidHostTest").dependencies {
            implementation(libs.compose.uiTestJunit4)
            implementation(libs.compose.uiTestManifest)
            implementation(libs.roborazzi.core)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.testExt.junit)
            implementation(libs.androidx.activity.compose)
            implementation(libs.compose.uiTestManifest)
        }
    }
}
