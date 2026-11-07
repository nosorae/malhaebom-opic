package io.github.nosorae.aiopic.ui.featurelive

import io.github.nosorae.aiopic.engine.coach.LiveCoach
import kotlinx.coroutines.CoroutineScope

/**
 * 세션 하나를 돌릴 코치를 만든다. 앱 층이 시험 팩의 코치 프로필, 사용자 키로 여는 연결, 플랫폼 오디오, 저장소를 묶는다.
 * [question] 은 질문 글이다. 코치는 [scope] 가 끝나면 연결을 닫는다.
 */
fun interface CoachFactory {
    suspend fun create(question: String, targetGrade: String, scope: CoroutineScope): LiveCoach
}
