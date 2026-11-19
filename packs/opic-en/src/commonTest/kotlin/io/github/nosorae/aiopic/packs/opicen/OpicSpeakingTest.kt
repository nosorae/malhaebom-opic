package io.github.nosorae.aiopic.packs.opicen

import io.github.nosorae.aiopic.engine.speaking.Cue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OpicSpeakingTest {
    private val sections = listOf("instruction") + Cue.entries.map { it.name.lowercase() }
    private val tools = """[{"name":"pause","description":"p"}]"""

    private fun file(names: List<String>) = PromptFile.parse("promptVersion: s-test\n\n" + names.joinToString("\n\n") { "# $it\n$it text" })

    @Test
    fun `F16_7 지시 절과 알림 절마다 글을 나눠 담는다`() {
        val profile = speakingProfile(file(sections), tools)

        assertEquals("instruction text" to "wrap text", profile.instruction to profile.cues[Cue.Wrap])
        assertEquals(Cue.entries.toSet(), profile.cues.keys)
    }

    @Test
    fun `알림 절이 하나라도 없으면 만들지 않는다`() {
        assertFailsWith<NoSuchElementException> { speakingProfile(file(sections - "closing"), tools) }
    }
}
