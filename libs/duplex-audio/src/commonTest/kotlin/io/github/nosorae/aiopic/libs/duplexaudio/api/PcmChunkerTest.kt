package io.github.nosorae.aiopic.libs.duplexaudio.api

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PcmChunkerTest {
    @Test
    fun `정해진 길이로 자르고 남은 바이트는 다음 조각 앞에 붙인다`() {
        val chunker = PcmChunker(size = 4)
        val bytes = ByteArray(10) { it.toByte() }

        val first = chunker.push(bytes.copyOfRange(0, 6))
        val second = chunker.push(bytes.copyOfRange(6, 10))

        assertEquals(listOf(listOf<Byte>(0, 1, 2, 3)), first.map { it.toList() })
        assertContentEquals(listOf<Byte>(4, 5, 6, 7), second.single().toList())
    }
}
