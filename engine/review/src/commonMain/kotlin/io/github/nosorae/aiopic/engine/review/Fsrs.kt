package io.github.nosorae.aiopic.engine.review

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** 다시, 어려움, 알았음, 쉬움(F18.6). 순서가 FSRS 평가 값 1~4 다. */
enum class Rating { Again, Hard, Good, Easy }

/** 설정의 복습 양과 그 목표 기억률(F18.7). */
enum class ReviewAmount(val retention: Double) { Less(0.85), Normal(0.90), More(0.95) }

/*
 * FSRS-6 간격 계산. py-fsrs v6.3.3(MIT) 의 Scheduler 를 옮겼다. 라이선스는 THIRD_PARTY_LICENSES.md 에 있다.
 * 카드에 학습 단계 상태를 두지 않으므로 learning_steps, relearning_steps 를 비운 설정과 같다.
 * 같은 카드가 같은 날 같은 간격을 받도록 간격 흔들기(fuzz)는 하지 않는다.
 */
private val W = doubleArrayOf(
    0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796,
    1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
)
private val DECAY = -W[20]
private val FACTOR = 0.9.pow(1 / DECAY) - 1
private const val STABILITY_MIN = 0.001
private const val MAX_INTERVAL_DAYS = 36500L

private val Rating.grade get() = ordinal + 1

private fun initialDifficulty(grade: Int) = W[4] - exp(W[5] * (grade - 1)) + 1

private fun clampDifficulty(d: Double) = d.coerceIn(1.0, 10.0)

private fun nextDifficulty(d: Double, rating: Rating): Double {
    val delta = -W[6] * (rating.grade - 3)
    val damped = d + (10.0 - d) * delta / 9.0
    return clampDifficulty(W[7] * initialDifficulty(4) + (1 - W[7]) * damped)
}

private fun shortTermStability(s: Double, rating: Rating): Double {
    val increase = exp(W[17] * (rating.grade - 3 + W[18])) * s.pow(-W[19])
    return max(s * if (rating == Rating.Again) increase else max(increase, 1.0), STABILITY_MIN)
}

private fun nextStability(d: Double, s: Double, r: Double, rating: Rating): Double {
    val next = if (rating == Rating.Again) {
        min(W[11] * d.pow(-W[12]) * ((s + 1).pow(W[13]) - 1) * exp((1 - r) * W[14]), s / exp(W[17] * W[18]))
    } else {
        val hardPenalty = if (rating == Rating.Hard) W[15] else 1.0
        val easyBonus = if (rating == Rating.Easy) W[16] else 1.0
        s * (1 + exp(W[8]) * (11 - d) * s.pow(-W[9]) * (exp((1 - r) * W[10]) - 1) * hardPenalty * easyBonus)
    }
    return max(next, STABILITY_MIN)
}

private fun intervalDays(s: Double, retention: Double): Long {
    // py-fsrs 의 round() 와 같게 반올림 대상이 .5 이면 짝수 쪽으로 보낸다.
    val days = round(s / FACTOR * (retention.pow(1 / DECAY) - 1)).toLong()
    return days.coerceIn(1, MAX_INTERVAL_DAYS)
}

/** 지금 기억할 확률(FSRS retrievability). 새 카드는 0 이다. 지난 날수는 하루 단위로 내린다. */
fun ExpressionCard.retrievability(now: Instant): Double {
    val s = stability ?: return 0.0
    val last = lastReviewedAt ?: return 0.0
    val elapsed = max(0L, (now - last).inWholeDays)
    return (1 + FACTOR * elapsed / s).pow(DECAY)
}

/** 평가 하나를 반영한 카드. 복습 값을 다시 계산하고 복습 횟수를, 다시면 다시 누른 횟수도 올린다(F18.6). */
fun ExpressionCard.review(rating: Rating, now: Instant, amount: ReviewAmount): ExpressionCard {
    val d = difficulty
    val s = stability
    val (nextS, nextD) = when {
        d == null || s == null -> max(W[rating.ordinal], STABILITY_MIN) to clampDifficulty(initialDifficulty(rating.grade))
        lastReviewedAt != null && (now - lastReviewedAt).inWholeDays < 1 -> shortTermStability(s, rating) to nextDifficulty(d, rating)
        else -> nextStability(d, s, retrievability(now), rating) to nextDifficulty(d, rating)
    }
    return copy(
        difficulty = nextD,
        stability = nextS,
        dueAt = now + intervalDays(nextS, amount.retention).days,
        lastReviewedAt = now,
        reviewCount = reviewCount + 1,
        againCount = againCount + if (rating == Rating.Again) 1 else 0,
    )
}
