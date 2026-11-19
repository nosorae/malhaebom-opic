package io.github.nosorae.aiopic.engine.answer.model

import io.github.nosorae.aiopic.engine.session.model.AnalysisError
import kotlin.time.Instant

/**
 * 분석 작업 하나(#205). 답변 분석이나 회차 등급을 보낼 일이 생기면 DB 에 한 줄로 남고, 이 줄이 작업 상태의 유일한 기준이다.
 * 플랫폼 대기열은 이 표를 비우는 일만 하고, 화면은 이 표를 구독해 무엇을 기다리는지 보인다.
 * [targetId] 는 [kind] 에 따라 답변 id, 회차 id, 연습 id 다.
 */
data class AnalysisJob(
    val kind: JobKind,
    val targetId: String,
    val state: JobState,
    /** 다시 보낼 실패(429, 5xx, 시간 초과)로 쓴 횟수. 오프라인은 세지 않는다. */
    val attempts: Int = 0,
    /** [JobState.RetryScheduled] 와 [JobState.WaitingNetwork] 일 때 다음에 보낼 시각. */
    val nextAttemptAt: Instant? = null,
    /** [JobState.Failed] 의 이유. */
    val error: AnalysisError? = null,
    val updatedAt: Instant,
)

enum class JobKind {
    Answer,
    Grade,

    /** 연습 정리(F17.4). 대화 전문으로 표현 카드를 만든다. */
    Cards,
}

enum class JobState {
    /** 차례를 기다린다. */
    Queued,

    /** 보내고 답을 기다린다. 앱이 이 상태로 죽으면 다음에 켤 때 [Queued] 로 돌린다. */
    Running,

    /** 연결이 없어 보내지 못했다. 연결되면 다시 보낸다. */
    WaitingNetwork,

    /** 다시 보낼 실패라 [AnalysisJob.nextAttemptAt] 에 다시 보낸다. */
    RetryScheduled,
    Succeeded,
    Failed,
    ;

    /** 아직 끝나지 않았다. 화면은 기다림을 보인다. */
    val active: Boolean get() = this != Succeeded && this != Failed
}
