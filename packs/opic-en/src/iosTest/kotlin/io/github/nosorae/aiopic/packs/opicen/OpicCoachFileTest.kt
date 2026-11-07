package io.github.nosorae.aiopic.packs.opicen

import io.github.nosorae.aiopic.engine.coach.CoachTools
import io.github.nosorae.aiopic.engine.coach.MaterialKind
import io.github.nosorae.aiopic.engine.coach.toolError
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

/** 실제 코치 지시와 도구 선언 파일을 Res 로 읽어 엔진 규칙과 맞는지 본다. Res 를 읽는 테스트라 iOS 시뮬레이터에서만 돈다. */
class OpicCoachFileTest {
    private suspend fun declarations() = coachProfile("Tell me about your home.", "IM3").tools.associateBy { it.getValue("name").jsonPrimitive.content }

    private fun JsonObject.params() = get("parameters")?.jsonObject

    private fun JsonObject.enum(key: String) = params()!!.getValue("properties").jsonObject.getValue(key).jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }

    /** 선언의 required 필드를 모두 채운 인자. enum 이 있으면 첫 값을 쓴다. */
    private fun JsonObject.validArgs() = JsonObject(
        params()?.getValue("required")?.jsonArray.orEmpty().associate { field ->
            val name = field.jsonPrimitive.content
            name to JsonPrimitive(runCatching { enum(name).first() }.getOrDefault("x"))
        },
    )

    @Test
    fun `F11_5 도구 선언 이름은 엔진 도구 셋이다`() = runTest {
        assertEquals(setOf(CoachTools.ADVANCE_PHASE, CoachTools.SAVE_MATERIAL, CoachTools.SAVE_EXPRESSION), declarations().keys)
    }

    @Test
    fun `선언의 required 를 채운 인자는 엔진 toolError 를 통과한다`() = runTest {
        declarations().forEach { (name, declaration) -> assertNull(toolError(name, declaration.validArgs()), name) }
    }

    @Test
    fun `선언의 required 를 하나라도 빼면 엔진 toolError 가 막는다`() = runTest {
        declarations().forEach { (name, declaration) ->
            val args = declaration.validArgs()
            args.keys.forEach { assertNotNull(toolError(name, JsonObject(args - it)), "$name 에서 $it 를 뺐다") }
        }
    }

    @Test
    fun `F12_2 save_material 의 kind 는 엔진 소재 종류 네 칸이다`() = runTest {
        assertEquals(MaterialKind.entries.map { it.name.lowercase() }, declarations().getValue(CoachTools.SAVE_MATERIAL).enum("kind"))
    }

    @Test
    fun `F12_3 save_expression 의 grade 는 목표 등급 목록이다`() = runTest {
        assertEquals(OpicEn.targetGrades, declarations().getValue(CoachTools.SAVE_EXPRESSION).enum("grade"))
    }

    @Test
    fun `F11_2 F11_3 단계마다 영어 전용 두 문장 묻기 규칙과 질문과 목표 등급이 들어간다`() = runTest {
        val profile = coachProfile("Tell me about your home.", "IM3")

        profile.instructions.forEach { (phase, text) ->
            listOf("Speak English only.", "reply in English", "At most two sentences per turn.", "Ask, do not write the answer", "Tell me about your home.", "Target grade is IM3", OpicEn.targetGrades.joinToString())
                .forEach { assertTrue(it in text, "$phase 지시에 '$it' 가 없다") }
            assertTrue("{{" !in text, "$phase 지시에 채우지 않은 빈칸이 있다")
        }
    }

    @Test
    fun `스크립트 프롬프트 빈칸은 질문 등급 길이 받아쓰기 메모 표현 리허설이다`() = runTest {
        assertEquals(setOf("question", "grade", "length", "transcript", "materials", "expressions", "rehearsals"), OpicPrompt.Script.load().placeholders)
    }

    @Test
    fun `다듬기 프롬프트 빈칸은 질문 등급 길이 고친 스크립트 표현이다`() = runTest {
        assertEquals(setOf("question", "grade", "length", "script", "expressions"), OpicPrompt.ScriptPolish.load().placeholders)
    }
}
