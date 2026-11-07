package io.github.nosorae.aiopic.tools.eval

import io.github.nosorae.aiopic.libs.livegemini.LiveConfig
import io.github.nosorae.aiopic.libs.livegemini.LiveEvent
import io.github.nosorae.aiopic.libs.livegemini.LiveSession
import io.github.nosorae.aiopic.libs.livegemini.LiveSocket
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoachEvalTest {
    private val tools = coachTools(File(PROMPTS, "coach-tools.json").readText())
    private val scenes = Json.decodeFromString<List<CoachScene>>(File(SCENES).readText())

    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun scenesHaveKoreanUserTurnsAndEveryPhaseHasAnInstruction() {
        val file = PromptFile.parse(File(PROMPTS, "coach.md").readText())

        assertTrue(scenes.size in 2..3)
        assertTrue(scenes.any { scene -> scene.turns.any { !isEnglishOnly(it) } })
        scenes.forEach { scene ->
            val instruction = coachInstruction(file, scene)
            assertContains(instruction, "Speak English only.")
            assertContains(instruction, "Target grade is ${scene.grade}")
            assertContains(instruction, scene.question)
        }
    }

    @Test
    fun notesGoAtTheEndLikeTheEngine() {
        val file = PromptFile.parse("promptVersion: c-v1\n# common\nQ {{question}} {{grade}}\n# feedback\nfeedback text")
        val scene = CoachScene("s", "Q1", "IH", "feedback", listOf("hi"), notes = listOf("rehearsal 1: I went."))

        assertEquals("Q Q1 IH\n\nfeedback text\n\nNotes so far:\nrehearsal 1: I went.", coachInstruction(file, scene))
    }

    @Test
    fun argsAreCheckedAgainstTheDeclarations() {
        assertNull(argError(tools, "save_material", args("""{"kind":"place","text":"Busan"}""")))
        assertNull(argError(tools, "advance_phase", JsonObject(emptyMap())))
        assertNotNull(argError(tools, "save_material", args("""{"kind":"weather","text":"rain"}""")))
        assertNotNull(argError(tools, "save_expression", args("""{"user_said":"좋았다","english":"It was great"}""")))
        assertNotNull(argError(tools, "save_expression", args("""{"user_said":"좋았다","english":"It was great","grade":"NL"}""")))
        assertNotNull(argError(tools, "save_note", JsonObject(emptyMap())))
    }

    @Test
    fun hangulMeansNotEnglishOnly() {
        assertTrue(isEnglishOnly("Where did you go?"))
        assertTrue(!isEnglishOnly("Where did you go? 부산?"))
    }

    @Test
    fun talkSendsTurnsAsTextAndRecordsRepliesToolCallsAndTokens() = runBlocking {
        val socket = FakeSocket(
            listOf(
                """{"serverContent":{"outputTranscription":{"text":"Nice! "}}}""",
                """{"toolCall":{"functionCalls":[{"id":"1","name":"save_material","args":{"kind":"place","text":"Busan"}}]}}""",
                """{"serverContent":{"outputTranscription":{"text":"Who went with you?"}}}""",
                """{"usageMetadata":{"promptTokenCount":100,"responseTokenCount":20}}""",
                """{"serverContent":{"turnComplete":true}}""",
            ),
        )
        val config = LiveConfig("test-model", "instruction", "Puck", tools)

        val run = talk(LiveSession(open = { socket }), "test-key", config, listOf("부산에 갔어요"))

        val turn = run.turns.single()
        assertEquals("Nice! Who went with you?", turn.coach)
        assertEquals(ToolUse("save_material", args("""{"kind":"place","text":"Busan"}"""), null), turn.calls.single())
        assertEquals(100 to 20, run.promptTokens to run.responseTokens)
        assertTrue(socket.sent.any { "\"text\":\"부산에 갔어요\"" in it })
        assertTrue(socket.sent.any { "toolResponse" in it && "\"ok\":true" in it })
    }

    @Test
    fun reportCountsEnglishOnlyRepliesAndValidToolCalls() {
        val scene = CoachScene("trip", "Q", "IH", "explore", listOf("a", "b"))
        val run = CoachRun(
            listOf(
                CoachTurn("부산", "Where?", listOf(ToolUse("save_material", JsonObject(mapOf("kind" to JsonPrimitive("place"))), "text is empty"))),
                CoachTurn("b", "좋아요", emptyList()),
            ),
            promptTokens = 300,
            responseTokens = 40,
        )

        val report = coachReport("opic-en-coach-v1", "test-model", listOf(scene to run))

        assertContains(report, "opic-en-coach-v1")
        assertContains(report, "영어만: 1/2")
        assertContains(report, "도구 인자 통과: 0/1")
        assertContains(report, "토큰: 입력 300, 출력 40")
        assertContains(report, "\n|---|---|---|---|\n| 부산 | Where? | 예 | save_material")
    }

    @Test
    fun talkWaitsForTheCoachToSpeakAfterAToolResponse() = runBlocking {
        val socket = FakeSocket(
            reply = listOf(
                """{"toolCall":{"functionCalls":[{"id":"1","name":"save_material","args":{"kind":"place","text":"Busan"}}]}}""",
                """{"serverContent":{"turnComplete":true}}""",
            ),
            afterTool = listOf("""{"serverContent":{"outputTranscription":{"text":"Who went with you?"}}}""", """{"serverContent":{"turnComplete":true}}"""),
        )

        val run = talk(LiveSession(open = { socket }), "test-key", LiveConfig("test-model", "instruction", "Puck", tools), listOf("부산에 갔어요"))

        assertEquals("Who went with you?", run.turns.single().coach)
    }

    /** setup 에 setupComplete 로, 글 하나에 [reply] 로, 도구 응답에 [afterTool] 로 답한다. */
    private class FakeSocket(private val reply: List<String>, private val afterTool: List<String> = emptyList()) : LiveSocket {
        val sent = mutableListOf<String>()
        private val inbox = Channel<String>(Channel.UNLIMITED)

        override suspend fun send(text: String) {
            sent += text
            when {
                "\"setup\"" in text -> inbox.send("""{"setupComplete":{}}""")
                "realtimeInput" in text -> reply.forEach { inbox.send(it) }
                "toolResponse" in text -> afterTool.forEach { inbox.send(it) }
            }
        }

        override suspend fun receive(): String? = inbox.receiveCatching().getOrNull()

        override val closeReason: LiveEvent.Disconnected? = null

        override suspend fun close() {
            inbox.close()
        }
    }
}
