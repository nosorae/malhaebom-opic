package io.github.nosorae.aiopic.ui.featurespeaking

import io.github.nosorae.aiopic.engine.speaking.AudioRoute
import io.github.nosorae.aiopic.engine.speaking.SpeakingState
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** 연습 중 화면은 서비스가 가진 세션을 지켜보기만 한다(F16.18, ADR 0038). */
class SpeakingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val control = FakePracticeControl()
    private val running = SpeakingState("p1", Instant.parse("2026-11-19T09:00:00Z"), 20.minutes, AudioRoute.Earphones, "cooking")

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = SpeakingViewModel(ObservePracticeUseCase(control), ControlPracticeUseCase(control))

    @Test
    fun `버튼은 앱 범위 연습에 명령으로 넘긴다`() = runTest(dispatcher) {
        control.state.value = running
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onIntent(SpeakingIntent.Send(PracticeCommand.Pause))
        viewModel.onIntent(SpeakingIntent.Send(PracticeCommand.Finish))
        advanceUntilIdle()

        assertEquals(listOf(PracticeCommand.Pause, PracticeCommand.Finish), control.sent)
    }

    @Test
    fun `F16_18 연습이 끝나면 그 연습의 정리 화면으로 간다`() = runTest(dispatcher) {
        control.state.value = running
        val viewModel = viewModel()
        advanceUntilIdle()

        control.state.value = running.copy(finished = true)
        advanceUntilIdle()

        assertEquals(SpeakingDestination.Summary("p1"), viewModel.uiState.value.navigation)
    }

    @Test
    fun `도는 연습이 없으면 화면을 닫는다`() = runTest(dispatcher) {
        val viewModel = viewModel()

        advanceUntilIdle()

        assertEquals(SpeakingDestination.Exit, viewModel.uiState.value.navigation)
    }
}
