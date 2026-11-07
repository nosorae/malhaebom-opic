package io.github.nosorae.aiopic.packs.opicen

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 실제 프롬프트 파일을 Res 로 읽어 본다. Android 호스트 테스트에는 앱 Context 가 없어 Compose 리소스를 못 읽으므로
 * iOS 시뮬레이터에서만 돈다. 파일 해석은 공통 테스트의 [PromptFileTest] 가 본다.
 */
class OpicPromptFileTest {
    @Test
    fun `F5_7 프롬프트 파일은 모두 서로 다른 promptVersion 을 가진다`() = runTest {
        val versions = OpicPrompt.entries.map { it.load().version }

        assertEquals(OpicPrompt.entries.size, versions.toSet().size, "$versions")
        assertTrue(versions.all { it.startsWith("opic-en-") }, "$versions")
    }

    @Test
    fun `답변 분석 프롬프트의 빈칸은 질문과 문항 유형과 이어 녹음 위치다`() = runTest {
        assertEquals(setOf("question", "questionType", "resumedAt"), OpicPrompt.Answer.load().placeholders)
    }

    @Test
    fun `회차 등급 프롬프트의 빈칸은 답변 피드백 묶음이다`() = runTest {
        assertEquals(setOf("answers"), OpicPrompt.SessionGrade.load().placeholders)
    }

    @Test
    fun `F6_1 출제 프롬프트의 빈칸은 주제 수준 재조정 약점 유형 이미 낸 질문 순서다`() = runTest {
        assertEquals(setOf("topics", "level", "recalibration", "weakTypes", "asked", "order"), OpicPrompt.Questions.load().placeholders)
    }

    @Test
    fun `F5_3 회차 등급 프롬프트는 예아니오 체크리스트 문장 C1 부터 C10 을 담는다`() = runTest {
        val ids = Regex("""^(C\d+): \S""", RegexOption.MULTILINE).findAll(OpicPrompt.SessionGrade.load().body).map { it.groupValues[1] }.toList()

        assertEquals((1..10).map { "C$it" }, ids)
    }

    @Test
    fun `241 답변 분석 프롬프트는 들리지 않는 말을 받아쓰지 말고 무음 표시를 돌려주라고 한다`() = runTest {
        val prompt = OpicPrompt.Answer.load()

        assertEquals("opic-en-answer-v5", prompt.version)
        assertTrue("Never invent" in prompt.body, prompt.body)
        assertTrue("\"silent\"" in prompt.body, prompt.body)
    }
}
