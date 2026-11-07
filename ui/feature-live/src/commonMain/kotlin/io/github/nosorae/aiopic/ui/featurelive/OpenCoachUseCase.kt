package io.github.nosorae.aiopic.ui.featurelive

import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.engine.coach.LiveCoach
import io.github.nosorae.aiopic.libs.duplexaudio.api.DuplexAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * 세션 하나를 돌릴 코치를 만든다. 앱 층이 시험 팩의 코치 프로필, 사용자 키로 여는 연결, 플랫폼 오디오, 저장소를 묶는다.
 * [question] 은 질문 글이다. 코치는 [scope] 가 끝나면 연결을 닫는다.
 */
fun interface CoachFactory {
    suspend fun create(question: String, targetGrade: String, scope: CoroutineScope): LiveCoach
}

/** 저장된 세션이면 이어 가고(F11.8), 없으면 새로 시작한다. */
@Inject
class OpenCoachUseCase(private val factory: CoachFactory) {
    suspend operator fun invoke(sessionId: String, questionId: String, question: String, targetGrade: String, scope: CoroutineScope): LiveCoach = factory.create(question, targetGrade, scope).also { if (!it.open(sessionId)) it.start(sessionId, questionId, targetGrade) }
}

/** 내 목소리 크기와 코치 목소리 크기(0~1). 지금 말하는 쪽의 목소리 띠에 쓴다(F12.1). */
@Inject
class ObserveCoachLevelsUseCase(private val audio: DuplexAudio) {
    operator fun invoke(): Flow<Pair<Float, Float>> = combine(audio.micLevel, audio.playbackLevel, ::Pair)
}
