package io.github.nosorae.aiopic.engine.review

import io.github.nosorae.aiopic.engine.answer.store.OpicDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** 플랫폼마다 메모리 DB 를 만든다. Android 는 호스트 JVM, iOS 는 시뮬레이터에서 같은 테스트를 돈다. */
expect fun inMemoryDatabase(): OpicDatabase

class CardStoreTest {
    private val db = inMemoryDatabase()
    private val seoul = TimeZone.of("Asia/Seoul")
    private val store = CardStore(db, seoul)
    private val now = Instant.parse("2026-11-19T09:00:00Z")

    @AfterTest
    fun close() = db.close()

    private fun card(createdAt: Instant = now - 1.hours) = ExpressionCard(
        kind = CardKind.Correction,
        said = "I go to school yesterday.",
        better = "I went to school yesterday.",
        meaning = "어제 학교에 갔다.",
        errorType = ErrorType.Tense,
        context = "주말에 한 일을 말하다가",
        practiceId = "p1",
        createdAt = createdAt,
        dueAt = createdAt,
    )

    @Test
    fun `F17_8 저장한 카드를 만든 시각이 최근인 것부터 낸다`() = runTest {
        val old = store.save(card(now - 2.days))
        val new = store.save(card(now - 1.days))

        assertEquals(listOf(card(now - 1.days).copy(id = new), card(now - 2.days).copy(id = old)), store.cards().first())
    }

    @Test
    fun `F18_8 평가하면 복습 값이 카드 줄에 바로 쓰인다`() = runTest {
        val id = store.save(card())

        store.rate(id, Rating.Good, now, ReviewAmount.Normal)

        assertEquals(card().copy(id = id).review(Rating.Good, now, ReviewAmount.Normal), store.cards().first().single())
    }

    @Test
    fun `F18_9 평가하면 기기 시간대의 그날이 복습한 날로 남는다`() = runTest {
        val id = store.save(card())

        store.rate(id, Rating.Again, Instant.parse("2026-11-18T16:00:00Z"), ReviewAmount.Normal)

        assertEquals(setOf(20776L), store.reviewDays().first())
    }

    @Test
    fun `F18_9 복습한 날은 최근 8주만 두고 오래된 날은 쓸 때 지운다`() = runTest {
        val id = store.save(card(now - 60.days))
        listOf(56, 55, 0).forEach { store.rate(id, Rating.Good, now - it.days, ReviewAmount.Normal) }

        val today = now.localDay(seoul)

        assertEquals(setOf(today - 55, today), store.reviewDays().first())
    }

    @Test
    fun `F18_3 보관한 카드는 보관 표시가 남는다`() = runTest {
        val id = store.save(card())

        store.archive(id, archived = true)

        assertTrue(store.cards().first().single().archived)
    }

    @Test
    fun `F18_3 지운 카드는 목록에서 빠진다`() = runTest {
        val keep = store.save(card(now - 2.days))
        val gone = store.save(card())

        store.delete(gone)

        assertEquals(listOf(keep), store.cards().first().map { it.id })
    }
}
