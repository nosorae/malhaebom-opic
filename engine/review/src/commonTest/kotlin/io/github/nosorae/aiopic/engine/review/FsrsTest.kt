package io.github.nosorae.aiopic.engine.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * 기대값은 py-fsrs v6.3.3(MIT, github.com/open-spaced-repetition/py-fsrs)의
 * `Scheduler(desired_retention=r, learning_steps=(), relearning_steps=(), enable_fuzzing=False)` 와
 * 기본 가중치로 같은 입력을 돌려 얻었다. 시작 시각은 py-fsrs 테스트와 같은 2022-11-29T12:30:00Z 다.
 */
class FsrsTest {
    private val start = Instant.parse("2022-11-29T12:30:00Z")
    private val newCard = ExpressionCard(
        kind = CardKind.Correction,
        said = "I go to school yesterday.",
        better = "I went to school yesterday.",
        meaning = "어제 학교에 갔다.",
        errorType = ErrorType.Tense,
        context = "주말에 한 일을 말하다가",
        dueAt = start,
    )

    private fun ExpressionCard.reviewAll(vararg steps: Pair<Duration, Rating>, amount: ReviewAmount = ReviewAmount.Normal): ExpressionCard {
        var at = start
        return steps.fold(this) { card, (wait, rating) ->
            at += wait
            card.review(rating, at, amount)
        }
    }

    private fun ExpressionCard.intervalDays() = (dueAt - lastReviewedAt!!).inWholeDays

    @Test
    fun `F18_6 py-fsrs test_memo_state 의 평가 순서를 넣으면 같은 안정도와 난이도가 나온다`() {
        // py-fsrs tests/test_basic.py test_memo_state 의 기대값과 같다.
        val card = newCard.reviewAll(
            0.days to Rating.Again,
            0.days to Rating.Good,
            1.days to Rating.Good,
            3.days to Rating.Good,
            8.days to Rating.Good,
            21.days to Rating.Good,
        )

        assertEquals(53.62691, card.stability!!, 1e-4)
        assertEquals(6.3574867, card.difficulty!!, 1e-4)
        assertEquals(54, card.intervalDays())
    }

    @Test
    fun `F18_6 새 카드의 첫 평가는 평가마다 정해진 안정도와 난이도와 간격을 준다`() {
        val expected = mapOf(
            Rating.Again to Triple(0.212, 6.4133, 1L),
            Rating.Hard to Triple(1.2931, 5.112170705601056, 1L),
            Rating.Good to Triple(2.3065, 2.118103970459016, 2L),
            Rating.Easy to Triple(8.2956, 1.0, 8L),
        )

        expected.forEach { (rating, e) ->
            val card = newCard.review(rating, start, ReviewAmount.Normal)

            assertEquals(e.first, card.stability!!, 1e-9, "$rating 안정도")
            assertEquals(e.second, card.difficulty!!, 1e-9, "$rating 난이도")
            assertEquals(e.third, card.intervalDays(), "$rating 간격")
        }
    }

    @Test
    fun `F18_7 목표 기억률이 높을수록 같은 평가 순서의 간격이 짧다`() {
        val ratings = listOf(Rating.Good, Rating.Good, Rating.Good, Rating.Again, Rating.Good, Rating.Hard, Rating.Easy)
        val expected = mapOf(
            ReviewAmount.Less to listOf(4L, 31, 173, 8, 27, 58, 197),
            ReviewAmount.Normal to listOf(2L, 11, 46, 3, 8, 14, 40),
            ReviewAmount.More to listOf(1L, 3, 8, 1, 2, 2, 5),
        )

        val actual = ReviewAmount.entries.associateWith { amount ->
            var card = newCard
            ratings.map { rating ->
                // 매번 다음 복습 시각에 맞춰 평가한다. 새 카드의 다음 복습 시각은 시작 시각이다.
                card = card.review(rating, card.dueAt, amount)
                card.intervalDays()
            }
        }

        assertEquals(expected, actual)
    }

    @Test
    fun `F18_7 복습 양은 적게 85 보통 90 많이 95 퍼센트다`() {
        assertEquals(listOf(0.85, 0.90, 0.95), ReviewAmount.entries.map { it.retention })
    }

    @Test
    fun `기억할 확률은 마지막 복습 뒤 지난 날수로 줄고 새 카드는 0이다`() {
        val card = newCard.review(Rating.Good, start, ReviewAmount.Normal)

        val actual = listOf(0, 1, 3, 10).map { card.retrievability(start + it.days + 5.hours) }

        assertEquals(0.0, newCard.retrievability(start))
        listOf(1.0, 0.9468474993825461, 0.8809479557659419, 0.7743669167614039)
            .zip(actual)
            .forEach { (e, a) -> assertEquals(e, a, 1e-9) }
    }

    @Test
    fun `F18_6 평가하면 복습 횟수가 오르고 다시면 다시 누른 횟수도 오른다`() {
        val card = newCard.reviewAll(0.days to Rating.Good, 2.days to Rating.Again)

        assertEquals(2, card.reviewCount)
        assertEquals(1, card.againCount)
        assertEquals(start + 2.days, card.lastReviewedAt)
    }
}
