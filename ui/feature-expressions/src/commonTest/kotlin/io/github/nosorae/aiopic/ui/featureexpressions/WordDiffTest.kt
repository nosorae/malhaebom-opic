package io.github.nosorae.aiopic.ui.featureexpressions

import kotlin.test.Test
import kotlin.test.assertEquals

class WordDiffTest {
    @Test
    fun `F18_5 바뀐 낱말은 내가 한 말에서 빠진 것과 더 나은 표현에 들어간 것으로 나뉜다`() {
        val ops = diffWords("I go to school yesterday.", "I went to school yesterday.")

        assertEquals(
            listOf(WordOp.Same("I"), WordOp.Removed("go"), WordOp.Added("went"), WordOp.Same("to"), WordOp.Same("school"), WordOp.Same("yesterday.")),
            ops,
        )
    }

    @Test
    fun `F18_4 고칠 표현의 앞면은 틀린 부분을 빈칸 하나로 가린다`() {
        assertEquals("I ____ to school yesterday.", maskedSaid(diffWords("I go to school yesterday.", "I went to school yesterday.")))
    }

    @Test
    fun `F18_4 빠뜨린 낱말도 그 자리를 빈칸으로 보인다`() {
        assertEquals("I have ____ dog.", maskedSaid(diffWords("I have dog.", "I have a dog.")))
    }
}
