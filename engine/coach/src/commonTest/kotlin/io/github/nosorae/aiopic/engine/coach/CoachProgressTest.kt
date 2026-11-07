package io.github.nosorae.aiopic.engine.coach

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class CoachProgressTest {
    private val start = CoachState("c1", "q1", "Tell me.", "IH", Instant.fromEpochSeconds(0))

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    private fun CoachState.after(vararg events: CoachEvent) = events.fold(this, ::reduce)

    @Test
    fun `F11_5 코치가 advance_phase 를 부르면 다음 단계로 간다`() {
        val state = start.after(CoachEvent.ToolCalled(CoachTools.ADVANCE_PHASE, JsonObject(emptyMap())))

        assertEquals(CoachPhase.Express, state.phase)
    }

    @Test
    fun `F11_5 사용자가 다음 단계를 고르면 다음 단계로 가고 앞 단계 핸들을 버린다`() {
        val state = start.after(CoachEvent.HandleReceived("h1", Instant.fromEpochSeconds(1)), CoachEvent.NextPhase)

        assertEquals(CoachPhase.Express, state.phase)
        assertNull(state.resumeHandle)
    }

    @Test
    fun `피드백 다음은 스크립트 정리이고 세션이 끝난다`() {
        val state = start.after(CoachEvent.NextPhase, CoachEvent.NextPhase, CoachEvent.NextPhase, CoachEvent.NextPhase)

        assertEquals(CoachPhase.Script to CoachStatus.Finished, state.phase to state.status)
    }

    @Test
    fun `F11_7 대화 25분이면 안내 상태가 된다`() {
        assertFalse(start.after(CoachEvent.TimePassed(24.minutes)).timeWarning)
        assertTrue(start.after(CoachEvent.TimePassed(24.minutes), CoachEvent.TimePassed(1.minutes)).timeWarning)
    }

    @Test
    fun `F11_7 대화 30분이면 어느 단계에 있든 스크립트 정리로 간다`() {
        val state = start.after(CoachEvent.NextPhase, CoachEvent.TimePassed(29.minutes), CoachEvent.TimePassed(1.minutes))

        assertEquals(CoachPhase.Script to CoachStatus.Finished, state.phase to state.status)
    }

    @Test
    fun `F12_2 save_material 은 kind 가 넷 중 하나이고 글이 있을 때만 저장한다`() {
        val state = start.after(
            CoachEvent.ToolCalled(CoachTools.SAVE_MATERIAL, args("kind" to "place", "text" to "a small cafe")),
            CoachEvent.ToolCalled(CoachTools.SAVE_MATERIAL, args("kind" to "weather", "text" to "rainy")),
            CoachEvent.ToolCalled(CoachTools.SAVE_MATERIAL, args("kind" to "feeling", "text" to " ")),
            CoachEvent.ToolCalled(CoachTools.SAVE_MATERIAL, args("text" to "no kind")),
        )

        assertEquals(listOf(Material(MaterialKind.Place, "a small cafe")), state.materials)
    }

    @Test
    fun `버린 도구 호출에는 에러 글이 있고 받은 호출에는 없다`() {
        assertNull(toolError(CoachTools.SAVE_MATERIAL, args("kind" to "event", "text" to "trip")))
        assertNotNull(toolError(CoachTools.SAVE_MATERIAL, args("kind" to "weather", "text" to "rainy")))
        assertNotNull(toolError(CoachTools.SAVE_EXPRESSION, args("user_said" to "좋았어", "english" to "It was great")))
        assertNotNull(toolError("unknown", JsonObject(emptyMap())))
    }

    @Test
    fun `F12_3 save_expression 은 세 인자가 다 있으면 저장한다`() {
        val state = start.after(CoachEvent.ToolCalled(CoachTools.SAVE_EXPRESSION, args("user_said" to "좋았어", "english" to "It was a blast", "grade" to "IH")))

        assertEquals(listOf(Expression("좋았어", "It was a blast", "IH")), state.expressions)
    }

    @Test
    fun `F13_2 리허설 받아쓰기는 리허설 단계에서만 받고 받으면 핸들을 버리고 피드백 단계로 간다`() {
        val state = start.after(
            CoachEvent.Rehearsed("too early"),
            CoachEvent.NextPhase,
            CoachEvent.NextPhase,
            CoachEvent.HandleReceived("h1", Instant.fromEpochSeconds(1)),
            CoachEvent.Rehearsed("first"),
            CoachEvent.Rehearsed("in feedback"),
        )

        assertEquals(listOf("first"), state.rehearsals)
        assertEquals(CoachPhase.Feedback, state.phase)
        assertNull(state.resumeHandle)
    }

    @Test
    fun `F13_3 리허설을 건너뛰면 첫 리허설 전에는 피드백 없이 스크립트 정리로 가고 두 번째에서는 피드백으로 간다`() {
        val rehearse = start.after(CoachEvent.NextPhase, CoachEvent.NextPhase)
        val second = rehearse.after(CoachEvent.Rehearsed("first"), CoachEvent.RehearseAgain, CoachEvent.NextPhase)

        assertEquals(CoachPhase.Script to CoachStatus.Finished, rehearse.after(CoachEvent.NextPhase).let { it.phase to it.status })
        assertEquals(CoachPhase.Feedback to listOf("first"), second.phase to second.rehearsals)
    }

    @Test
    fun `F13_3 리허설 받아쓰기는 두 번까지 받는다`() {
        val state = start.after(
            CoachEvent.NextPhase,
            CoachEvent.NextPhase,
            CoachEvent.Rehearsed("first"),
            CoachEvent.RehearseAgain,
            CoachEvent.Rehearsed("second"),
            CoachEvent.RehearseAgain,
            CoachEvent.Rehearsed("third"),
        )

        assertEquals(listOf("first", "second"), state.rehearsals)
    }

    @Test
    fun `F13_3 피드백에서 한 번 더 리허설하면 리허설 단계로 돌아가고 두 번을 채우면 돌아가지 않는다`() {
        val feedback = start.after(CoachEvent.NextPhase, CoachEvent.NextPhase, CoachEvent.Rehearsed("first"))
        val second = feedback.after(CoachEvent.RehearseAgain, CoachEvent.Rehearsed("second"))

        assertEquals(CoachPhase.Rehearse, feedback.after(CoachEvent.RehearseAgain).phase)
        assertTrue(feedback.canRehearseAgain)
        assertFalse(second.canRehearseAgain)
        assertEquals(CoachPhase.Feedback, second.after(CoachEvent.RehearseAgain).phase)
        assertEquals(listOf("first", "second"), second.rehearsals)
    }

    @Test
    fun `받아쓰기 조각은 같은 단계 같은 사람이면 한 줄로 잇는다`() {
        val state = start.after(
            CoachEvent.Heard(Speaker.Coach, "Where"),
            CoachEvent.Heard(Speaker.Coach, " do you live?"),
            CoachEvent.Heard(Speaker.User, "Seoul"),
        )

        assertEquals(listOf(Line(CoachPhase.Explore, Speaker.Coach, "Where do you live?"), Line(CoachPhase.Explore, Speaker.User, "Seoul")), state.lines)
    }

    @Test
    fun `F12_4 사용량은 단계별로 모으고 모달리티 단가로 비용을 낸다`() {
        val state = start.after(
            CoachEvent.Used(TokenUsage(input = mapOf(Modality.Audio to 500_000, Modality.Text to 1_000_000), output = mapOf(Modality.Audio to 100_000))),
            CoachEvent.Used(TokenUsage(input = mapOf(Modality.Audio to 500_000))),
            CoachEvent.NextPhase,
            CoachEvent.Used(TokenUsage(output = mapOf(Modality.Audio to 1_000_000))),
        )

        val explore = state.usage.getValue(CoachPhase.Explore)
        assertEquals(TokenUsage(input = mapOf(Modality.Audio to 1_000_000, Modality.Text to 1_000_000), output = mapOf(Modality.Audio to 100_000)), explore)
        // 음성 입력 100만 $3 + 글 입력 100만 $0.75 + 음성 출력 10만 $1.2
        assertEquals(4.95, explore.costUsd, 1e-9)
        assertEquals(16.95, state.costUsd, 1e-9)
    }

    @Test
    fun `다음 단계 지시에는 단계 지시와 메모와 최근 받아쓰기가 들어간다`() {
        val profile = CoachProfile(mapOf(CoachPhase.Express to "Teach expressions."), emptyList())
        val state = start.after(
            CoachEvent.Heard(Speaker.User, "I go to a cafe."),
            CoachEvent.ToolCalled(CoachTools.SAVE_MATERIAL, args("kind" to "place", "text" to "a small cafe")),
            CoachEvent.NextPhase,
        )

        assertEquals("Teach expressions.\n\nNotes so far:\nplace: a small cafe\nuser: I go to a cafe.", instruction(profile, state))
    }

    @Test
    fun `끝난 세션은 더 바뀌지 않는다`() {
        val finished = start.after(CoachEvent.TimePassed(30.minutes))

        assertEquals(finished, finished.after(CoachEvent.NextPhase, CoachEvent.Heard(Speaker.User, "hi")))
    }

    @Test
    fun `F12_2 사용자가 소재 칸을 고치면 그 종류의 메모를 고친 글 하나로 바꾼다`() {
        val state = start.after(
            CoachEvent.ToolCalled(CoachTools.SAVE_MATERIAL, args("kind" to "place", "text" to "a cafe")),
            CoachEvent.ToolCalled(CoachTools.SAVE_MATERIAL, args("kind" to "place", "text" to "near my office")),
            CoachEvent.ToolCalled(CoachTools.SAVE_MATERIAL, args("kind" to "person", "text" to "my sister")),
            CoachEvent.MaterialEdited(MaterialKind.Place, "a small cafe near my office"),
        )

        assertEquals(listOf(Material(MaterialKind.Person, "my sister"), Material(MaterialKind.Place, "a small cafe near my office")), state.materials)
    }

    @Test
    fun `F12_2 소재 칸을 비우면 그 종류의 메모를 지운다`() {
        val state = start.after(
            CoachEvent.ToolCalled(CoachTools.SAVE_MATERIAL, args("kind" to "place", "text" to "a cafe")),
            CoachEvent.MaterialEdited(MaterialKind.Place, " "),
        )

        assertEquals(emptyList(), state.materials)
    }

    @Test
    fun `F12_3 사용자가 표현 카드를 고치면 그 자리의 카드를 바꾸고 없는 자리면 그대로 둔다`() {
        val saved = start.after(CoachEvent.ToolCalled(CoachTools.SAVE_EXPRESSION, args("user_said" to "좋았어", "english" to "It was great", "grade" to "IM2")))
        val edited = Expression("좋았어", "It was a blast", "IM2")

        assertEquals(listOf(edited), saved.after(CoachEvent.ExpressionEdited(0, edited)).expressions)
        assertEquals(saved, saved.after(CoachEvent.ExpressionEdited(1, edited)))
    }
}
