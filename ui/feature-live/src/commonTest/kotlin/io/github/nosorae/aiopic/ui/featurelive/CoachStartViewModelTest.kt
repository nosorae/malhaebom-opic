package io.github.nosorae.aiopic.ui.featurelive

import kotlinx.coroutines.CompletableDeferred
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

class CoachStartViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = FakeTargetGradeStore()
    private val question = CoachQuestion("q1", "Tell me about a trip.")
    private val picked = CompletableDeferred<CoachQuestion?>()
    private var picks = 0

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = CoachStartViewModel(
        ObserveTargetGradeUseCase(store),
        SetTargetGradeUseCase(store),
        PickCoachQuestionUseCase {
            picks++
            picked.await()
        },
    )

    @Test
    fun `F10_2 시트를 열면 설정의 목표 등급이 골라져 있다`() = runTest(dispatcher) {
        store.targetGrade.value = "AL"

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(CoachStartUiState(grades = store.targetGrades, selected = "AL"), viewModel.uiState.value)
    }

    @Test
    fun `F10_3 시트에서 등급을 고르면 전역 값이 바뀐다`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onIntent(CoachStartIntent.SelectTargetGrade("IM2"))
        advanceUntilIdle()

        assertEquals("IM2", store.targetGrade.value)
    }

    @Test
    fun `F10_3 다른 곳에서 전역 값을 바꾸면 열려 있던 시트도 따라간다`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        store.setTargetGrade("IM3")
        advanceUntilIdle()

        assertEquals("IM3", viewModel.uiState.value.selected)
    }

    @Test
    fun `F10_1 시작을 누르면 질문을 고르는 동안 처리 중이고 고르면 그 질문과 목표 등급으로 대화를 연다`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onIntent(CoachStartIntent.Start)
        advanceUntilIdle()
        val starting = viewModel.uiState.value.starting
        picked.complete(question)
        advanceUntilIdle()

        assertEquals(true to CoachStartDestination.Start(question, "IH"), starting to viewModel.uiState.value.navigation)
    }

    @Test
    fun `시작을 빠르게 두 번 눌러도 질문은 한 번만 고르고 대화도 하나만 연다`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onIntent(CoachStartIntent.Start)
        viewModel.onIntent(CoachStartIntent.Start)
        picked.complete(question)
        advanceUntilIdle()
        viewModel.onIntent(CoachStartIntent.Start)
        advanceUntilIdle()

        assertEquals(1, picks)
    }

    @Test
    fun `낼 질문이 없으면 시트를 닫는다`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onIntent(CoachStartIntent.Start)
        picked.complete(null)
        advanceUntilIdle()

        assertEquals(CoachStartDestination.Close, viewModel.uiState.value.navigation)
    }
}
