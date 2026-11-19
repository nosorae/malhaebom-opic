package io.github.nosorae.aiopic.ui.featureexpressions

import io.github.nosorae.aiopic.engine.review.Rating
import io.github.nosorae.aiopic.engine.review.ReviewAmount

/** 복습 화면이 쓰는 설정 값. 앱 층이 플랫폼 설정 저장소로 구현해서 프로세스가 끝나도 남는다(F18.5, F18.7, F18.8). */
interface ReviewSettings {
    /** 소리 없이. 처음 꺼짐이다. */
    suspend fun silent(): Boolean

    suspend fun setSilent(on: Boolean)

    /** 복습 양. 처음 보통이다. */
    suspend fun reviewAmount(): ReviewAmount

    /** 이번 묶음. 아직 묶음을 만든 적이 없으면 null 이다. */
    suspend fun reviewBatch(): ReviewBatch?

    suspend fun saveReviewBatch(batch: ReviewBatch)
}

/**
 * 복습 한 묶음. [day] 는 묶음을 만든 앱의 하루(localDay)이고 [results] 는 누른 카드 id 와 결과다. 처음 봄 카드의 다음은 결과가 null 이다.
 * 결과를 카드 id 와 짝지어 두어서 묶음 중간 카드를 지워도 어느 카드를 눌렀는지 어긋나지 않는다.
 */
data class ReviewBatch(val day: Long, val cardIds: List<Long>, val results: List<Pair<Long, Rating?>> = emptyList())

/** 영어 문장을 기기 음성으로 읽는다(F18.3, F18.4). 앱 층이 기기 음성 합성으로 구현한다. */
fun interface ExpressionVoice {
    suspend fun speak(text: String)
}
