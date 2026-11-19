package io.github.nosorae.aiopic.ui.featurespeaking

import io.github.nosorae.aiopic.engine.speaking.AudioRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** 연습 시작 시트(F16.2, F16.3). */
class SpeakingStartViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val control = FakePracticeControl()
    private val settings = FakeSpeakingSettings(minutes = 30)
    private val consent = FakeLiveConsent()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(earphones: Boolean = true) = SpeakingStartViewModel(
        earphones = earphones,
        loadSpeakingStartUseCase = LoadSpeakingStartUseCase(settings, consent),
        startSpeakingUseCase = StartSpeakingUseCase(control, settings, consent),
    )

    @Test
    fun `F16_2 설정의 기본 연습 시간이 골라져 있고 이어폰이 없으면 스피커가 골라져 있다`() = runTest(dispatcher) {
        val viewModel = viewModel(earphones = false)

        advanceUntilIdle()

        assertEquals(30 to AudioRoute.Speaker, viewModel.uiState.value.minutes to viewModel.uiState.value.route)
    }

    @Test
    fun `F16_3 키가 없으면 시작할 수 없다`() = runTest(dispatcher) {
        settings.key = false
        val viewModel = viewModel()

        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.canStart)
    }

    @Test
    fun `F16_2 고른 시간과 소리 환경으로 마이크를 받은 뒤 연습을 시작하고 연습 중 화면으로 간다`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onIntent(SpeakingStartIntent.SelectMinutes(10))
        viewModel.onIntent(SpeakingStartIntent.SelectRoute(AudioRoute.Speaker))

        viewModel.onIntent(SpeakingStartIntent.Start)
        assertTrue(viewModel.uiState.value.needsMic)
        viewModel.onIntent(SpeakingStartIntent.MicGranted)
        advanceUntilIdle()

        val setup = control.started.single().second
        assertEquals(Triple(10.minutes, AudioRoute.Speaker, SpeakingStartDestination.Running), Triple(setup.planned, setup.route, viewModel.uiState.value.navigation))
    }

    @Test
    fun `F16_3 동의 전이면 시작을 누를 때 실시간 음성 전송 동의를 남긴다`() = runTest(dispatcher) {
        consent.given = false
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onIntent(SpeakingStartIntent.Start)
        viewModel.onIntent(SpeakingStartIntent.MicGranted)
        advanceUntilIdle()

        assertTrue(consent.given)
    }

    @Test
    fun `F16_3 마이크를 거부하면 시작하지 않고 설정 안내를 보인다`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onIntent(SpeakingStartIntent.Start)
        viewModel.onIntent(SpeakingStartIntent.MicDenied)
        advanceUntilIdle()

        assertEquals(emptyList(), control.started)
        assertTrue(viewModel.uiState.value.micDenied)
    }
}
