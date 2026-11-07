package io.github.nosorae.aiopic.ui.featuresettings

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

/** 코치 목표 등급(F10.3, F10.4). 시작 시트 쪽은 `CoachStartViewModelTest` 가 본다. */
class TargetGradeSettingTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = FakeSettingsStore()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `F10_4 설정 화면은 고를 등급 다섯과 지금 목표 등급을 보인다`() = runTest(dispatcher) {
        val viewModel = settingsViewModel(store, FakeKeyVault())
        advanceUntilIdle()

        assertEquals(store.targetGrades to "IH", viewModel.uiState.value.let { it.targetGrades to it.targetGrade })
    }

    @Test
    fun `F10_3 설정에서 고르면 전역 값이 바뀐다`() = runTest(dispatcher) {
        val viewModel = settingsViewModel(store, FakeKeyVault())
        advanceUntilIdle()

        viewModel.onIntent(SettingsIntent.SelectTargetGrade("AL"))
        advanceUntilIdle()

        assertEquals("AL", store.targetGrade.value)
    }

    @Test
    fun `F10_3 시작 시트에서 바꾼 값이 열려 있던 설정 화면에도 보인다`() = runTest(dispatcher) {
        val viewModel = settingsViewModel(store, FakeKeyVault())
        advanceUntilIdle()

        store.setTargetGrade("IM1")
        advanceUntilIdle()

        assertEquals("IM1", viewModel.uiState.value.targetGrade)
    }
}
