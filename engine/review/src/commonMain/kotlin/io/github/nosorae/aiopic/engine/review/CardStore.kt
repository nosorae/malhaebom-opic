package io.github.nosorae.aiopic.engine.review

import io.github.nosorae.aiopic.engine.answer.store.CardRow
import io.github.nosorae.aiopic.engine.answer.store.OpicDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/** 복습한 날을 두는 기간. 오늘을 넣어 8주다(F18.9). */
private const val KEEP_DAYS = 56

/**
 * 표현 카드와 복습한 날을 앱 DB 에 저장한다(F17.8). 기기에만 두고 다른 기기와 맞추지 않는다.
 * 복습한 날은 [timeZone] 의 로컬 날짜 번호로 남긴다.
 */
class CardStore(db: OpicDatabase, private val timeZone: TimeZone) {
    private val dao = db.reviewDao()

    /** 모든 카드, 만든 시각이 최근인 것부터. 보관한 카드도 낸다. */
    fun cards(): Flow<List<ExpressionCard>> = dao.cards().map { rows -> rows.map { it.toCard() } }

    /** 지운 카드면 null. 말로 복습이 판정 전 복습 값을 남길 때 읽는다(F16.8). */
    suspend fun card(id: Long): ExpressionCard? = dao.card(id)?.toCard()

    /** 새 카드(id 0)는 넣고, 있는 카드는 고친 내용으로 덮어쓴다. 카드 id 를 돌려준다. */
    suspend fun save(card: ExpressionCard): Long = dao.save(card.toRow())

    /** 평가를 카드 줄에 바로 쓰고 그날을 복습한 날로 남긴다(F18.8, F18.9). 지운 카드면 아무것도 하지 않는다. */
    suspend fun rate(id: Long, rating: Rating, now: Instant, amount: ReviewAmount) {
        val card = dao.card(id)?.toCard() ?: return
        val today = now.localDay(timeZone)
        dao.rated(card.review(rating, now, amount).toRow(), today, keepFrom = today - KEEP_DAYS + 1)
    }

    /** 보관하거나 꺼낸다(F18.3). */
    suspend fun archive(id: Long, archived: Boolean) = dao.archive(id, archived)

    suspend fun delete(id: Long) = dao.delete(id)

    /** 복습한 날, 로컬 날짜 번호. [reviewDaysThisWeek] 에 넘긴다. */
    fun reviewDays(): Flow<Set<Long>> = dao.days().map { it.toSet() }
}

/** 카드 줄. 연습 정리가 연습 기록과 한 트랜잭션으로 카드를 쓸 때도 쓴다(F17.4). */
fun ExpressionCard.toRow() = CardRow(
    id = id,
    kind = kind.name,
    said = said,
    better = better,
    meaning = meaning,
    errorType = errorType?.name,
    context = context,
    practiceId = practiceId,
    createdAt = createdAt.toEpochMilliseconds(),
    firstSeen = firstSeen,
    archived = archived,
    difficulty = difficulty,
    stability = stability,
    dueAt = dueAt.toEpochMilliseconds(),
    lastReviewedAt = lastReviewedAt?.toEpochMilliseconds(),
    reviewCount = reviewCount,
    againCount = againCount,
)

private fun CardRow.toCard() = ExpressionCard(
    id = id,
    kind = CardKind.valueOf(kind),
    said = said,
    better = better,
    meaning = meaning,
    errorType = errorType?.let(ErrorType::valueOf),
    context = context,
    createdAt = Instant.fromEpochMilliseconds(createdAt),
    dueAt = Instant.fromEpochMilliseconds(dueAt),
    difficulty = difficulty,
    stability = stability,
    lastReviewedAt = lastReviewedAt?.let(Instant::fromEpochMilliseconds),
    reviewCount = reviewCount,
    againCount = againCount,
    firstSeen = firstSeen,
    archived = archived,
    practiceId = practiceId,
)
