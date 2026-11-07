package io.github.nosorae.aiopic.packs.opicen

import io.github.nosorae.aiopic.engine.coach.CoachPhase
import io.github.nosorae.aiopic.engine.coach.CoachState
import io.github.nosorae.aiopic.engine.coach.Expression
import io.github.nosorae.aiopic.engine.coach.Line
import io.github.nosorae.aiopic.engine.coach.Material
import io.github.nosorae.aiopic.engine.coach.MaterialKind
import io.github.nosorae.aiopic.engine.coach.ScriptParts
import io.github.nosorae.aiopic.engine.coach.Speaker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class OpicCoachTest {
    private val file = PromptFile.parse(
        """
        promptVersion: opic-en-coach-test

        # common
        Q: {{question}} G: {{grade}}

        # explore
        explore text

        # express
        express text

        # feedback
        feedback text
        """.trimIndent(),
    )
    private val tools = """[{"name":"advance_phase","description":"next"}]"""

    @Test
    fun `F11_1 지시 파일의 단계 절마다 공통 절을 앞에 붙인 지시를 만든다`() {
        val profile = coachProfile(file, tools, "Tell me about your home.", "IH")

        assertEquals("Q: Tell me about your home. G: IH\n\nexplore text", profile.instructions[CoachPhase.Explore])
    }

    @Test
    fun `F13_1 리허설과 스크립트 단계는 연결이 없어 지시가 없다`() {
        val profile = coachProfile(file, tools, "Q", "IH")

        assertEquals(setOf(CoachPhase.Explore, CoachPhase.Express, CoachPhase.Feedback), profile.instructions.keys)
    }

    @Test
    fun `도구 선언 파일의 항목을 그대로 넘긴다`() {
        val profile = coachProfile(file, tools, "Q", "IH")

        assertEquals("advance_phase", profile.tools.single()["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `F10_2 목표 등급 목록에 없는 등급이면 만들지 않는다`() {
        assertFailsWith<IllegalArgumentException> { coachProfile(file, tools, "Q", "NL") }
    }

    @Test
    fun `F14_2 스크립트 길이 표는 목표 등급마다 하나다`() {
        assertEquals(OpicEn.targetGrades, OpicEn.scriptLengths.keys.toList())
    }

    @Test
    fun `F14_2 IH 스크립트는 1분 30초에서 2분 12에서 16문장이다`() {
        assertEquals(ScriptLength(90.seconds, 2.minutes, 12..16), OpicEn.scriptLengths["IH"])
    }

    @Test
    fun `F14_1 스크립트 프롬프트에 질문 등급 길이 받아쓰기 메모 표현 카드 리허설을 넣는다`() {
        val session = CoachState("s1", "q1", "IM2", Instant.fromEpochSeconds(0)).copy(
            lines = listOf(Line(CoachPhase.Explore, Speaker.User, "부산에 갔어요")),
            materials = listOf(Material(MaterialKind.Place, "Busan")),
            expressions = listOf(Expression("바다가 예뻤다", "the sea was stunning", "IM3")),
            rehearsals = listOf("I went to Busan."),
        )
        val script = PromptFile.parse("promptVersion: s-v1\n{{question}}|{{grade}}|{{length}}|{{transcript}}|{{materials}}|{{expressions}}|{{rehearsals}}")

        val prompt = scriptPrompt(script, "Tell me about a trip.", session)

        assertEquals("s-v1", prompt.version)
        assertTrue("Tell me about a trip.|IM2|about 1m spoken, 8 to 10 sentences|" in prompt.text, prompt.text)
        listOf("부산에 갔어요", "Busan", "the sea was stunning", "I went to Busan.").forEach { assertTrue(it in prompt.text, it) }
        assertEquals(OpicPrompt.Script.responseSchema.toString(), prompt.responseSchema)
    }

    @Test
    fun `F14_4 다듬기 프롬프트에 사용자가 고친 세 칸과 지금 목표 등급 길이를 넣는다`() {
        val polish = PromptFile.parse("promptVersion: p-v1\n{{question}}|{{grade}}|{{length}}|{{script}}|{{expressions}}")

        val prompt = polishPrompt(polish, "Q", ScriptParts("I live here.", "It is big.", "I love it."), emptyList(), "AL")

        assertEquals(
            """Q|AL|2m to 2m 30s spoken, 16 to 20 sentences|{"intro":"I live here.","body":"It is big.","closing":"I love it."}|[]""",
            prompt.text,
        )
    }

    @Test
    fun `F14_1 스크립트 응답 예시는 스키마 그대로 읽힌다`() {
        val response = Json.decodeFromString<ScriptResponse>("""{"intro":"a","body":"b","closing":"c","usedExpressions":["the sea was stunning"]}""")

        assertEquals(listOf("the sea was stunning"), response.usedExpressions)
    }
}
