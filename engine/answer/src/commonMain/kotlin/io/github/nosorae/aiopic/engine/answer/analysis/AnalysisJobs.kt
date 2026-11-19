package io.github.nosorae.aiopic.engine.answer.analysis

import io.github.nosorae.aiopic.engine.answer.model.AnalysisJob
import io.github.nosorae.aiopic.engine.answer.model.Answer
import io.github.nosorae.aiopic.engine.answer.model.AnswerAnalysis
import io.github.nosorae.aiopic.engine.answer.model.JobKind
import io.github.nosorae.aiopic.engine.answer.model.JobState
import io.github.nosorae.aiopic.engine.answer.model.ModelInfo
import io.github.nosorae.aiopic.engine.answer.model.UnscorableReason
import io.github.nosorae.aiopic.engine.answer.port.AnalysisResult
import io.github.nosorae.aiopic.engine.answer.port.Prompt
import io.github.nosorae.aiopic.engine.answer.port.Recording
import io.github.nosorae.aiopic.engine.answer.port.SavedResponses
import io.github.nosorae.aiopic.engine.answer.port.SessionGrader
import io.github.nosorae.aiopic.engine.answer.route.AnswerRouter
import io.github.nosorae.aiopic.engine.answer.store.OpicStore
import io.github.nosorae.aiopic.engine.session.model.AnalysisError
import io.github.nosorae.aiopic.engine.session.model.ExamSession
import io.github.nosorae.aiopic.engine.session.model.ExamStatus
import io.github.nosorae.aiopic.engine.session.model.GradeScale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** 답변 하나를 보낼 때 앱이 채워 주는 것. 고른 모델(F5.8), 시험 팩의 프롬프트, 조각을 이어 붙인 녹음이다. */
data class AnalysisRequest(val model: ModelInfo, val prompt: Prompt, val recording: Recording)

/** 회차 등급을 보낼 때 앱이 채워 주는 것. 고른 모델, 답변 피드백을 채운 시험 팩의 등급 프롬프트, 등급 체계, 답한 순서의 녹음이다. */
data class GradeRequest(val model: ModelInfo, val prompt: Prompt, val scale: GradeScale, val recordings: List<Recording>)

/** 다시 보낼 실패(429, 408, 5xx, 시간 초과, 온라인인데 연결 실패)는 처음 한 번 뒤로 이만큼 더 보낸다(F5.6). */
internal const val MAX_RETRIES = 3

/** 첫 재시도 전 대기. 다음부터 두 배씩 늘린다. 무료 키의 분당 한도가 풀리기를 기다리는 시간이다. */
private val FIRST_BACKOFF = 30.seconds

/** 오프라인이던 작업을 다시 보내 볼 간격. 플랫폼이 연결을 알려 주면 그보다 먼저 깨운다. */
private val NETWORK_RECHECK = 30.seconds

/** 답변 분석과 회차 등급의 다시 보내기 간격. 30초부터 두 배씩 늘린다. */
private val ANSWER_BACKOFF = List(MAX_RETRIES) { FIRST_BACKOFF * (1 shl it) }

/**
 * 다른 엔진이 맡는 작업의 일. 지금은 연습 정리(F17.4) 하나다. engine/answer 는 engine/speaking 을 모르므로 이 경계를 둔다.
 * 작업 줄, 다시 보내기, 앱을 켤 때 되돌리기는 [AnalysisJobs] 가 하고, 보내기와 결과 쓰기만 이 일이 한다.
 */
interface JobWork {
    /** 실패한 뒤 다시 보내기 전에 기다릴 시간. 길이가 다시 보내는 횟수다. */
    val backoff: List<Duration>

    /** 대상 하나를 처리한다. 실패하면 그 이유를 돌려준다. */
    suspend fun run(targetId: String): AnalysisError?

    /** 다시 보내기를 다 썼거나 다시 보내도 같은 실패라 실패로 끝났다. */
    suspend fun failed(targetId: String, error: AnalysisError)

    /** 앱을 켤 때 다시 넣을 대상. */
    suspend fun unfinished(): List<String>
}

/**
 * 분석 작업 표(#205, ADR 0032). 답변 분석과 회차 등급(F5.3)을 DB 의 작업 한 줄로 두고, 이 표만 보고 하나씩 보낸다.
 * 어떤 작업도 성공이나 실패로 끝난다. 다시 보낼 실패는 [MAX_RETRIES] 번까지 다시 보내고 다 쓰면 실패, 기기가 오프라인([online] 이 false)이면 횟수를 쓰지 않고
 * 네트워크를 기다리고, 한 번 보내기는 [timeout] 안에 끝나야 한다. 실패로 끝나면 답변([AnswerAnalysis.Failed])이나
 * 회차([ExamSession.gradeError])에도 이유를 남겨 화면이 다시 분석하기를 보인다.
 *
 * 플랫폼 대기열은 [drain] 을 부르는 일만 한다. Android 는 WorkManager 가, iOS 는 앱이 앞에 있을 때와 백그라운드 작업이 부른다.
 * [wake] 는 작업을 넣을 때 플랫폼 대기열을 깨운다. 한 프로세스에 하나만 만든다.
 */
class AnalysisJobs(
    private val store: OpicStore,
    private val router: AnswerRouter,
    private val grader: SessionGrader,
    private val consented: suspend () -> Boolean,
    /** 기기가 지금 연결돼 있는가. 연결 실패를 받았을 때 오프라인(횟수를 쓰지 않고 기다림)과 Gemini 만 닿지 않음(다시 보낼 실패)을 가른다. */
    private val online: () -> Boolean,
    private val answerRequest: suspend (Answer) -> AnalysisRequest,
    private val gradeRequest: suspend (List<Answer>) -> GradeRequest,
    private val wake: () -> Unit,
    private val clock: Clock,
    private val timeout: Duration = 2.minutes,
    /** 연습 정리(F17.4). 없으면 연습 정리 작업은 실패로 끝난다. */
    private val cards: JobWork? = null,
) {
    /** Android 의 두 WorkManager 작업처럼 [drain] 이 겹쳐 불려도 하나씩 보낸다. 무료 키의 분당 한도 때문이다. */
    private val draining = Mutex()

    /** 답변 분석을 넣는다(F5.1). 이미 기다리는 중이면 그대로 둔다. */
    suspend fun enqueueAnswer(answerId: String) = enqueue(JobKind.Answer, answerId)

    /** 끝낸 연습의 정리를 넣는다(F16.11, F17.4). 이미 기다리는 중이면 그대로 둔다. */
    suspend fun enqueueCards(practiceId: String) = enqueue(JobKind.Cards, practiceId)

    /**
     * 회차가 끝났고 답한 답변이 모두 분석됐으면(Done 또는 평가할 수 없음) 등급 작업을 넣는다(F5.3).
     * 등급을 내지 못한 회차는 사용자가 다시 분석하기를 누를 때까지 넣지 않는다([retrySession]).
     * 시간 부족으로 끝난 회차(F2.6)는 답한 문항만 본다. 마지막 답변 분석이 회차가 끝나기 전에 끝날 수 있어서
     * 답변 분석 뒤와 회차 저장 뒤 양쪽에서 부른다.
     */
    suspend fun requestGradeIfReady(sessionId: String) {
        val session = store.session(sessionId).first() ?: return
        if (session.grade != null || session.gradeError != null || analyzedAnswers(session) == null) return
        enqueue(JobKind.Grade, sessionId)
    }

    /** 분석하지 못한 답변을 기다림으로 돌려 다시 넣는다(#203). 실패가 아니면 아무것도 하지 않는다. */
    suspend fun retryAnswer(answerId: String) {
        val answer = store.answers().first().firstOrNull { it.id == answerId && it.analysis is AnswerAnalysis.Failed } ?: return
        // 답변을 먼저 기다림으로 바꾼다. 작업을 넣기 전에 앱이 죽어도 recover 가 기다림 답변을 다시 넣는다.
        store.save(answer.copy(analysis = AnswerAnalysis.Pending))
        enqueue(JobKind.Answer, answerId, restart = true)
    }

    /** 회차에서 분석하지 못한 답변을 모두 다시 넣고, 등급을 내지 못했으면 등급도 다시 요청한다(#203). */
    suspend fun retrySession(sessionId: String) {
        store.answers(sessionId).first().filter { it.analysis is AnswerAnalysis.Failed }.forEach { retryAnswer(it.id) }
        val session = store.session(sessionId).first() ?: return
        if (session.gradeError == null) return
        store.save(session.copy(gradeError = null))
        enqueue(JobKind.Grade, sessionId, restart = true)
    }

    /**
     * 남은 작업을 보내지 않고 [error] 로 끝낸다. 답변과 녹음은 남는다. 음성 전송 동의를 철회하면(F1.3) 동의 없음,
     * 사용자가 시스템 진행 표시에서 멈추면(#229) 취소로 끝낸다. 녹음을 보내지 않는 연습 정리는 동의 철회로 멈추지 않는다.
     */
    suspend fun cancelAll(error: AnalysisError = AnalysisError.NoConsent) = store.jobs().first().filter { it.state.active && !(error == AnalysisError.NoConsent && it.kind == JobKind.Cards) }.forEach { fail(it, error) }

    /**
     * 앱을 켤 때 한 번 부른다. 진행 중으로 남은 작업(앱이 보내는 중에 죽었다)을 대기로 돌리고,
     * 작업이 끝났거나 없는데 기다림인 답변(작업 표가 없던 버전에서 쌓였거나, 작업을 넣기 전에 앱이 죽었다)을 대기로 넣는다.
     * 등급을 기다리는 회차와 정리 전이거나 정리 실패인 연습(F17.4)도 다시 본다.
     */
    suspend fun recover() {
        val jobs = store.jobs().first()
        jobs.filter { it.state == JobState.Running }.forEach { store.save(it.copy(state = JobState.Queued, updatedAt = clock.now())) }
        val active = jobs.filter { it.kind == JobKind.Answer && it.state.active }.map { it.targetId }.toSet()
        store.answers().first().filter { it.analysis == AnswerAnalysis.Pending && it.id !in active }.forEach { enqueue(JobKind.Answer, it.id, restart = true) }
        store.sessions().first().forEach { requestGradeIfReady(it.id) }
        cards?.unfinished()?.forEach { enqueue(JobKind.Cards, it, restart = true) }
        wake()
    }

    /**
     * 보낼 때가 된 작업을 넣은 순서대로 하나씩 보낸다. 한 번 부를 때 작업마다 한 번만 보내고, 오프라인이면 거기서 멈춘다.
     * 다음에 다시 불러야 할 때까지 남은 시간을 돌려준다. 기다리는 작업이 없으면 null 이다.
     */
    suspend fun drain(): Duration? = draining.withLock {
        val tried = mutableSetOf<Pair<JobKind, String>>()
        while (true) {
            val now = clock.now()
            val job = store.jobs().first().firstOrNull { it.kind to it.targetId !in tried && it.dueAt(now) } ?: break
            tried += job.kind to job.targetId
            if (run(job) == AnalysisError.Offline) break
        }
        val now = clock.now()
        store.jobs().first().mapNotNull {
            when (it.state) {
                JobState.RetryScheduled -> (it.nextAttemptAt!! - now).coerceAtLeast(Duration.ZERO)

                // 오프라인에서 멈춰 남은 대기도 연결을 다시 볼 때 함께 보낸다. 보내는 사이 넣은 대기는 넣을 때 깨워서 따로 돈다.
                JobState.WaitingNetwork, JobState.Queued -> NETWORK_RECHECK

                else -> null
            }
        }.minOrNull()
    }

    private fun AnalysisJob.dueAt(now: Instant) = when (state) {
        JobState.Queued, JobState.WaitingNetwork -> true
        JobState.RetryScheduled -> nextAttemptAt!! <= now
        else -> false
    }

    private suspend fun enqueue(kind: JobKind, targetId: String, restart: Boolean = false) {
        val existing = store.jobs().first().firstOrNull { it.kind == kind && it.targetId == targetId }
        if (existing != null && existing.state.active) return
        if (existing?.state == JobState.Succeeded && !restart) return
        store.save(AnalysisJob(kind, targetId, JobState.Queued, updatedAt = clock.now()))
        wake()
    }

    /** 작업 하나를 보내고 상태를 남긴다. 받은 실패를 돌려준다. */
    private suspend fun run(job: AnalysisJob): AnalysisError? {
        // 연습 정리는 녹음이 아니라 대화 전문 글을 보낸다. 실시간 음성 전송 동의(F15.1)를 받고 한 연습이라 녹음 전송 동의(F1.3)를 보지 않는다.
        if (job.kind != JobKind.Cards && !consented()) return fail(job, AnalysisError.NoConsent)
        store.save(job.copy(state = JobState.Running, updatedAt = clock.now()))
        val saved = SavedResponses({ store.response(job.kind, job.targetId, it) }, { stage, text -> store.saveResponse(job.kind, job.targetId, stage, text) })
        val error = try {
            withContext(saved) {
                when (job.kind) {
                    JobKind.Answer -> analyzeAnswer(job.targetId)
                    JobKind.Grade -> grade(job.targetId)
                    JobKind.Cards -> if (cards == null) AnalysisError.Rejected else withTimeout(timeout) { cards.run(job.targetId) }
                }
            }
        } catch (_: TimeoutCancellationException) {
            AnalysisError.Retryable
        } catch (e: CancellationException) {
            // 플랫폼이 보내는 중에 멈췄다(백그라운드 시간 끝, 네트워크 조건 잃음). 프로세스가 살아 있으니 바로 대기로 돌린다.
            withContext(NonCancellable) { store.save(job.copy(state = JobState.Queued, updatedAt = clock.now())) }
            throw e
        } catch (_: Exception) {
            // 녹음 파일을 못 읽는 것처럼 예상 못한 실패도 끝없이 기다리지 않게 실패로 끝낸다.
            AnalysisError.Rejected
        }
        val now = clock.now()
        val backoff = if (job.kind == JobKind.Cards) cards?.backoff.orEmpty() else ANSWER_BACKOFF
        // 연습 정리는 읽을 수 없는 응답도 다시 보낸다(F17.4). 카드 만들기가 이미 한 번 다시 요청했고, 정리 실패는 앱을 켤 때까지 남는다.
        val retryable = error == AnalysisError.Retryable || error == AnalysisError.Offline || (job.kind == JobKind.Cards && error == AnalysisError.Rejected)
        when {
            error == null -> {
                store.save(job.copy(state = JobState.Succeeded, error = null, nextAttemptAt = null, updatedAt = now))
                if (job.kind == JobKind.Answer) store.answers().first().firstOrNull { it.id == job.targetId }?.examSessionId?.let { requestGradeIfReady(it) }
            }

            // 기기가 오프라인일 때만 횟수를 쓰지 않고 기다린다. 온라인인데 연결만 실패하면(TLS 오류 등) 다시 보낼 실패로 세어 끝없이 보내지 않는다(#211).
            error == AnalysisError.Offline && !online() -> store.save(job.copy(state = JobState.WaitingNetwork, nextAttemptAt = now + NETWORK_RECHECK, updatedAt = now))

            retryable && job.attempts < backoff.size -> store.save(
                job.copy(state = JobState.RetryScheduled, attempts = job.attempts + 1, nextAttemptAt = now + backoff[job.attempts], updatedAt = now),
            )

            else -> fail(job, error)
        }
        return error
    }

    /** 작업을 실패로 끝내고 답변이나 회차에 이유를 남긴다. 작업을 먼저 쓰고 결과를 쓴다. 그 사이 앱이 죽으면 recover 가 기다림 답변을 다시 넣는다. */
    private suspend fun fail(job: AnalysisJob, error: AnalysisError): AnalysisError {
        store.save(job.copy(state = JobState.Failed, error = error, nextAttemptAt = null, updatedAt = clock.now()))
        when (job.kind) {
            JobKind.Answer -> store.answers().first().firstOrNull { it.id == job.targetId && it.analysis == AnswerAnalysis.Pending }?.let { store.save(it.copy(analysis = AnswerAnalysis.Failed(error))) }
            JobKind.Grade -> store.session(job.targetId).first()?.let { store.save(it.copy(gradeError = error)) }
            JobKind.Cards -> cards?.failed(job.targetId, error)
        }
        return error
    }

    /** 답변 하나를 분석해 저장한다(F5.1). 이미 결과가 있거나 지워졌으면 부르지 않는다. */
    private suspend fun analyzeAnswer(answerId: String): AnalysisError? {
        // ponytail: 답변 하나를 찾으려고 전체를 읽는다. 답변이 수천 개로 늘면 저장소에 id 로 읽는 쿼리를 더한다.
        val answer = store.answers().first().firstOrNull { it.id == answerId }
        if (answer == null || answer.analysis !is AnswerAnalysis.Pending) return null
        // 말소리가 없던 답은 보내지 않는다. 보내면 모델이 질문에 맞는 답을 지어내 점수를 낼 수 있다(#241).
        if (answer.silent) return null.also { store.save(answer.copy(analysis = AnswerAnalysis.Unscorable(null, UnscorableReason.Silent))) }
        val (model, prompt, recording) = answerRequest(answer)
        // 시간 제한은 포트 호출만 감싼다. 앞뒤 DB 읽기, 쓰기가 제한에 걸려 중간에 끊기지 않게 한다. 포트 안의 원문 저장은 SavedResponses 가 제한 밖에서 끝낸다(#211).
        return when (val result = withTimeout(timeout) { router.analyze(answer, model, prompt, recording) }) {
            is AnalysisResult.Success -> null.also { store.save(result.value) }

            // 경로 고르기는 평가 불가를 답변에 넣어 Success 로 돌려준다. 여기 오지 않는다.
            is AnalysisResult.Unscorable -> AnalysisError.Rejected

            is AnalysisResult.Failed -> result.error
        }
    }

    /** 회차 등급을 받아 회차에 남긴다(F5.3). 이미 등급이 있거나 낼 수 없으면 부르지 않는다. */
    private suspend fun grade(sessionId: String): AnalysisError? {
        val session = store.session(sessionId).first() ?: return null
        if (session.grade != null) return null
        val answered = analyzedAnswers(session) ?: return null
        val (model, prompt, scale, recordings) = gradeRequest(answered)
        return when (val result = withTimeout(timeout) { grader.grade(model, prompt, scale, recordings) }) {
            // 등급을 기다리는 동안 회차가 바뀌었을 수 있어 최신 값에 등급만 얹는다.
            is AnalysisResult.Success -> null.also { store.save((store.session(sessionId).first() ?: session).copy(grade = result.value, gradeError = null)) }

            // 회차 전체를 평가할 수 없다는 답은 다시 보내도 같다. 기다림으로 남기지 않는다.
            is AnalysisResult.Unscorable -> AnalysisError.Rejected

            is AnalysisResult.Failed -> result.error
        }
    }

    /** 등급을 낼 수 있으면 답한 순서의 답변, 아니면 null. */
    private suspend fun analyzedAnswers(session: ExamSession): List<Answer>? {
        if (session.status == ExamStatus.InProgress || session.answerIds.isEmpty()) return null
        val byId = store.answers(session.id).first().associateBy { it.id }
        val answers = session.answerIds.mapNotNull(byId::get)
        return answers.takeIf { it.size == session.answerIds.size && it.all { answer -> answer.analysis is AnswerAnalysis.Done || answer.analysis is AnswerAnalysis.Unscorable } }
    }
}
