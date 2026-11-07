package io.github.nosorae.aiopic.apps.opic.shared

import io.github.nosorae.aiopic.engine.question.model.Question
import io.github.nosorae.aiopic.engine.question.model.QuestionType
import io.github.nosorae.aiopic.engine.question.model.SurveyAnswers
import io.github.nosorae.aiopic.libs.haptics.api.HapticEffect
import io.github.nosorae.aiopic.libs.haptics.api.Haptics
import io.github.nosorae.aiopic.ui.featuresettings.theme.ThemeMode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppSettingsTest {
    private val disk = mutableMapOf<String, String>()

    private fun reopen() = AppSettings(read = { disk[it] }, write = { key, value -> disk[key] = value })

    @Test
    fun `F9_1 처음 켜면 시스템 따라가기이고 모델은 고르지 않은 상태다`() = runTest {
        val settings = reopen()

        assertEquals(ThemeMode.System, settings.themeMode.value)
        assertNull(settings.selectedModelId())
    }

    @Test
    fun `F9_1 F5_8 고른 화면 모드와 모델은 앱을 다시 켜도 남는다`() = runTest {
        reopen().run {
            setThemeMode(ThemeMode.Dark)
            selectModel("gemini-3.5-flash-lite")
        }

        val reopened = reopen()

        assertEquals(ThemeMode.Dark, reopened.themeMode.value)
        assertEquals("gemini-3.5-flash-lite", reopened.selectedModelId())
    }

    @Test
    fun `F9_1 화면 모드를 고르면 구독하는 쪽이 바로 새 값을 본다`() = runTest {
        val settings = reopen()

        settings.setThemeMode(ThemeMode.Light)

        assertEquals(ThemeMode.Light, settings.themeMode.value)
    }

    @Test
    fun `저장된 화면 모드를 읽을 수 없으면 시스템 따라가기로 시작한다`() {
        disk["theme_mode"] = "Sepia"

        assertEquals(ThemeMode.System, reopen().themeMode.value)
    }

    @Test
    fun `F1_3 동의를 철회하면 앱을 다시 켜도 동의 전이다`() = runTest {
        reopen().run {
            give()
            revoke()
        }

        assertFalse(reopen().isGiven())
    }

    @Test
    fun `F2_7 답변 타이머는 기본으로 꺼져 있고 켜면 다시 켜도 남아 시험 화면 조립에 쓰인다`() = runTest {
        assertFalse(reopen().answerTimer)

        reopen().setAnswerTimerOn(true)

        assertTrue(reopen().answerTimer)
        assertTrue(reopen().answerTimerOn())
    }

    @Test
    fun `F6_3 불러온 문항은 내장 문항 뒤에 붙어 한 문항 연습 풀에 들어간다`() = runTest {
        val builtIn = Question("pack-1", "Tell me about yourself.", QuestionType("self-intro"), "self")
        val settings = reopen()
        assertEquals(listOf(builtIn), practicePool(settings) { listOf(builtIn) })

        settings.saveQuestionFile("""[{"id": "my-1", "text": "Describe your room.", "type": "description", "topic": "home"}]""")

        assertEquals(listOf(builtIn, Question("my-1", "Describe your room.", QuestionType("description"), "home")), practicePool(reopen()) { listOf(builtIn) })
    }

    @Test
    fun `서베이 답은 앱을 다시 켜도 남는다`() = runTest {
        assertNull(reopen().surveyAnswers())
        val answers = SurveyAnswers(mapOf("work" to "none"), listOf("movies", "golf"))

        reopen().saveSurveyAnswers(answers)

        assertEquals(answers, reopen().surveyAnswers())
    }

    @Test
    fun `한 문항 연습은 유형마다 서베이에서 고른 주제의 질문만 남기고 고른 주제가 없는 유형은 그대로 둔다`() {
        fun q(id: String, type: String, topic: String) = Question(id, id, QuestionType(type), topic)
        val pool = listOf(q("d-park", "description", "parks"), q("d-golf", "description", "golf"), q("self", "self-intro", "self"))

        assertEquals(listOf(q("d-golf", "description", "golf"), q("self", "self-intro", "self")), preferTopics(pool, listOf("golf")))
        assertEquals(pool, preferTopics(pool, emptyList()))
    }

    @Test
    fun `저장한 서베이 주제의 질문이 한 문항 연습 풀에 남는다`() = runTest {
        fun q(id: String, topic: String) = Question(id, id, QuestionType("description"), topic)
        val settings = reopen()
        settings.saveSurveyAnswers(SurveyAnswers(topics = listOf("golf")))

        assertEquals(listOf(q("golf", "golf")), practicePool(settings) { listOf(q("park", "parks"), q("golf", "golf")) })
    }

    @Test
    fun `진동은 기본으로 켜져 있고 끄면 재생하지 않는다`() = runTest {
        val played = mutableListOf<HapticEffect>()
        val settings = reopen()
        val haptics = settings.haptics(Haptics { played += it })

        assertTrue(settings.hapticsOn())
        haptics.play(HapticEffect.Selection)

        settings.setHapticsOn(false)
        haptics.play(HapticEffect.Success)

        assertFalse(reopen().hapticsOn())
        assertEquals(listOf(HapticEffect.Selection), played)
    }

    @Test
    fun `F10_4 목표 등급을 고른 적이 없으면 IH 다`() {
        assertEquals("IH", reopen().targetGrade.value)
    }

    @Test
    fun `F10_3 고른 목표 등급은 앱을 다시 켜도 남는다`() = runTest {
        reopen().setTargetGrade("AL")

        assertEquals("AL", reopen().targetGrade.value)
    }

    @Test
    fun `저장된 목표 등급이 목록에 없으면 IH 로 시작한다`() {
        disk["target_grade"] = "NL"

        assertEquals("IH", reopen().targetGrade.value)
    }
}
