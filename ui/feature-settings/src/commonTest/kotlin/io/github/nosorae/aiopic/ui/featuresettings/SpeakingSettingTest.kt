package io.github.nosorae.aiopic.ui.featuresettings

import io.github.nosorae.aiopic.engine.review.ReviewAmount
import io.github.nosorae.aiopic.ui.featuresettings.speaking.EndWait
import io.github.nosorae.aiopic.ui.featuresettings.speaking.SpeakingPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** 설정 탭의 말하기 연습 묶음과 복습 묶음(F19.1, F19.2). 값을 쓰는 쪽(시작 시트, 알림)은 앱 틀 테스트가 본다. */
class SpeakingSettingTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = FakeSettingsStore()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `F19_2 처음 값은 20분 보통 기다림 말로 끊기 켬 문법 안 짚음 말로 복습 켬 복습 보통 알림 꺼짐 오후 8시다`() = runTest(dispatcher) {
        val viewModel = settingsViewModel(store, FakeKeyVault())
        advanceUntilIdle()

        assertEquals(
            SpeakingPrefs(20, EndWait.Normal, bargeIn = true, grammarNudge = false, oralReview = true, amount = ReviewAmount.Normal, reminderOn = false, reminderAt = LocalTime(20, 0)),
            viewModel.uiState.value.speaking,
        )
    }

    @Test
    fun `F19_1 말하기 연습 묶음의 값을 바꾸면 저장된다`() = runTest(dispatcher) {
        val viewModel = settingsViewModel(store, FakeKeyVault())
        advanceUntilIdle()

        viewModel.onIntent(SettingsIntent.SetPracticeMinutes(30))
        viewModel.onIntent(SettingsIntent.SetEndWait(EndWait.Long))
        viewModel.onIntent(SettingsIntent.SetBargeIn(on = false))
        viewModel.onIntent(SettingsIntent.SetGrammarNudge(on = true))
        viewModel.onIntent(SettingsIntent.SetOralReview(on = false))
        advanceUntilIdle()

        val expected = SpeakingPrefs(minutes = 30, endWait = EndWait.Long, bargeIn = false, grammarNudge = true, oralReview = false)
        assertEquals(expected, store.speaking)
        assertEquals(expected, viewModel.uiState.value.speaking)
    }

    @Test
    fun `F19_1 복습 양과 알림과 알림 시각을 바꾸면 저장된다`() = runTest(dispatcher) {
        val viewModel = settingsViewModel(store, FakeKeyVault())
        advanceUntilIdle()

        viewModel.onIntent(SettingsIntent.SetReviewAmount(ReviewAmount.More))
        viewModel.onIntent(SettingsIntent.SetReviewReminder(on = true))
        viewModel.onIntent(SettingsIntent.SetReminderTime(LocalTime(7, 30)))
        advanceUntilIdle()

        val expected = SpeakingPrefs(amount = ReviewAmount.More, reminderOn = true, reminderAt = LocalTime(7, 30))
        assertEquals(expected, store.speaking)
        assertEquals(expected, viewModel.uiState.value.speaking)
    }

    @Test
    fun `저장한 값은 설정을 다시 열어도 남는다`() = runTest(dispatcher) {
        store.speaking = SpeakingPrefs(minutes = 10, reminderOn = true)

        val viewModel = settingsViewModel(store, FakeKeyVault())
        advanceUntilIdle()

        assertEquals(SpeakingPrefs(minutes = 10, reminderOn = true), viewModel.uiState.value.speaking)
    }
}
