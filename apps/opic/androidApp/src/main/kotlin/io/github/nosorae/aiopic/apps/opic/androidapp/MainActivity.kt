package io.github.nosorae.aiopic.apps.opic.androidapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.nosorae.aiopic.apps.opic.shared.OpicApp
import io.github.nosorae.aiopic.apps.opic.shared.routeFromJson
import io.github.nosorae.aiopic.apps.opic.shared.routeFromLink
import io.github.nosorae.aiopic.ui.featureonboarding.start.StartViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 스플래시는 super.onCreate 전에 설치해야 한다. 시작 분기(F1.0)가 첫 화면을 정할 때까지 잡아 둔다.
        val splash = installSplashScreen()
        // 모든 화면을 시스템 바 아래까지 그린다. 인셋은 공용 화면 틀(MontageScreen)이 처리한다(ADR 0021).
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = (application as OpicApplication).graph
        // 화면을 돌려도 분기를 다시 하지 않게 Activity 의 ViewModel 로 둔다.
        val start = ViewModelProvider(this, viewModelFactory { initializer { graph.startViewModel } })[StartViewModel::class]
        splash.setKeepOnScreenCondition { start.uiState.value.keepSplash }
        // 분석 끝 알림을 눌러 열었으면 그 리포트를, 복습 링크로 열었으면 복습 화면을 시작 화면 위에 쌓는다(#229, F19.5).
        val opened = intent.getStringExtra(AnalysisNotifications.ROUTE)?.let(::routeFromJson) ?: intent.dataString?.let(::routeFromLink)
        setContent { OpicApp(graph, start, opened) }
    }
}
