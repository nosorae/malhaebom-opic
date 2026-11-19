package io.github.nosorae.aiopic.packs.opicen

import io.github.nosorae.aiopic.engine.speaking.QuestionKind
import io.github.nosorae.aiopic.engine.speaking.SpeakingProfile
import io.github.nosorae.aiopic.engine.speaking.SpeakingTools
import io.github.nosorae.aiopic.engine.speaking.toolError
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 실제 말하기 연습 지시와 도구 선언 파일을 Res 로 읽어 엔진 규칙과 맞는지 본다. Res 를 읽는 테스트라 iOS 시뮬레이터에서만 돈다. */
class OpicSpeakingFileTest {
    private suspend fun declarations() = speakingProfile().tools.associateBy { it.getValue("name").jsonPrimitive.content }

    private fun JsonObject.params() = get("parameters")?.jsonObject

    private fun JsonObject.enum(key: String) = params()!!.getValue("properties").jsonObject.getValue(key).jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }

    /** 선언의 required 필드를 모두 채운 인자. enum 이 있으면 첫 값을, card_id 는 수를 쓴다. */
    private fun JsonObject.validArgs() = JsonObject(
        params()?.getValue("required")?.jsonArray.orEmpty().associate { field ->
            val name = field.jsonPrimitive.content
            name to if (name == "card_id") JsonPrimitive(1) else JsonPrimitive(runCatching { enum(name).first() }.getOrDefault("x"))
        },
    )

    @Test
    fun `F16_9 도구 선언 이름은 명령 일곱을 포함한 엔진 도구 전부다`() = runTest {
        val all = setOf(
            SpeakingTools.PAUSE, SpeakingTools.RESUME, SpeakingTools.HINT, SpeakingTools.WHAT_IN_ENGLISH, SpeakingTools.NEXT_QUESTION, SpeakingTools.SWITCH_TOPIC,
            SpeakingTools.STOP, SpeakingTools.ASK_QUESTION, SpeakingTools.CLARIFY, SpeakingTools.FLAG_BROKEN, SpeakingTools.JUDGE_CARD, SpeakingTools.SAVE_EXPRESSION,
        )

        assertEquals(all, declarations().keys)
    }

    @Test
    fun `선언의 required 를 채운 인자는 엔진 toolError 를 통과하고 하나라도 빼면 막힌다`() = runTest {
        declarations().forEach { (name, declaration) ->
            val args = declaration.validArgs()
            assertNull(toolError(name, args), name)
            args.keys.forEach { assertNotNull(toolError(name, JsonObject(args - it)), "$name 에서 $it 를 뺐다") }
        }
    }

    @Test
    fun `F16_6 ask_question 의 kind 는 엔진 유형 여섯이다`() = runTest {
        assertEquals(QuestionKind.entries.map { it.toolName }.toSet(), declarations().getValue(SpeakingTools.ASK_QUESTION).enum("kind").toSet())
    }

    @Test
    fun `F16_22 지시 빈칸은 엔진이 채우는 값과 같다`() = runTest {
        val file = PromptFile.parse(Res.readBytes("files/prompts/speaking.md").decodeToString())

        assertEquals(SpeakingProfile.KEYS + "question", file.placeholders)
    }

    @Test
    fun `F16_7 지시에 영어 전용 두 문장과 연습 중 고친 문장 말하지 않기가 들어간다`() = runTest {
        val text = speakingProfile().instruction

        listOf("Speak English only.", "At most two sentences per turn.", "the user talks for more than half", "Do not say corrected sentences").forEach { assertTrue(it in text, it) }
    }
}
