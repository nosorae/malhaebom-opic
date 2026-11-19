package io.github.nosorae.aiopic.engine.speaking

import io.github.nosorae.aiopic.engine.answer.analysis.AnalysisJobs
import io.github.nosorae.aiopic.engine.answer.model.Feedback
import io.github.nosorae.aiopic.engine.answer.model.JobKind
import io.github.nosorae.aiopic.engine.answer.model.JobState
import io.github.nosorae.aiopic.engine.answer.model.ModelInfo
import io.github.nosorae.aiopic.engine.answer.port.AnalysisResult
import io.github.nosorae.aiopic.engine.answer.port.AudioAnswerAnalyzer
import io.github.nosorae.aiopic.engine.answer.port.Prompt
import io.github.nosorae.aiopic.engine.answer.port.Recording
import io.github.nosorae.aiopic.engine.answer.port.SessionGrader
import io.github.nosorae.aiopic.engine.answer.port.TextAnalysis
import io.github.nosorae.aiopic.engine.answer.port.Transcriber
import io.github.nosorae.aiopic.engine.answer.port.Transcript
import io.github.nosorae.aiopic.engine.answer.port.TranscriptAnalyzer
import io.github.nosorae.aiopic.engine.answer.route.AnswerRouter
import io.github.nosorae.aiopic.engine.answer.store.OpicDatabase
import io.github.nosorae.aiopic.engine.answer.store.OpicStore
import io.github.nosorae.aiopic.engine.question.model.Question
import io.github.nosorae.aiopic.engine.review.CardKind
import io.github.nosorae.aiopic.engine.review.CardStore
import io.github.nosorae.aiopic.engine.review.ErrorType
import io.github.nosorae.aiopic.engine.review.ExpressionCard
import io.github.nosorae.aiopic.engine.session.model.AnalysisError
import io.github.nosorae.aiopic.engine.session.model.GradeScale
import io.github.nosorae.aiopic.engine.session.model.SessionGrade
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** 연습 정리 작업(F17.4, F17.5, F17.9). 가짜 작성기를 진짜 분석 작업 표와 메모리 DB 에 넣어 돌린다. */
class CardJobTest {
    private val seoul = TimeZone.of("Asia/Seoul")

    /** 서울 2026-11-20 00:50 에 시작해 20분 말한 연습. 새벽 4시 전이라 앱의 하루로는 11-19 에 끝났다(F18.11). */
    private val startedAt = Instant.parse("2026-11-19T15:50:00Z")
    private var now = startedAt + 30.minutes
    private val clock = object : Clock {
        override fun now() = this@CardJobTest.now
    }

    private val writer = FakeWriter()
    private lateinit var db: OpicDatabase
    private lateinit var speaking: SpeakingStore
    private lateinit var cards: CardStore
    private lateinit var jobs: AnalysisJobs

    /** DB 쿼리를 테스트 시계 위에서 돌린다. 분석 작업 표의 시간 제한이 실제 스레드를 기다리다 테스트 시계로 끝나지 않게 한다. */
    private fun test(block: suspend TestScope.() -> Unit) = runTest {
        db = inMemoryDatabase(StandardTestDispatcher(testScheduler))
        speaking = SpeakingStore(db)
        cards = CardStore(db, seoul)
        jobs = AnalysisJobs(
            store = OpicStore(db),
            router = AnswerRouter(NoAnswers, NoAnswers, NoAnswers),
            grader = NoAnswers,
            consented = { false },
            online = { true },
            answerRequest = { error("답변 분석은 넣지 않는다") },
            gradeRequest = { error("회차 등급은 넣지 않는다") },
            wake = {},
            clock = clock,
            cards = CardJob(speaking, cards, writer, clock, seoul),
        )
        try {
            block()
        } finally {
            db.close()
        }
    }

    private val transcript = listOf(
        Line(Speaker.Coach, "What did you do last weekend?"),
        Line(Speaker.User, "I go to the beach and I ended up staying late."),
        Line(Speaker.Coach, "You can say we had to mitigate the risk."),
        Line(Speaker.User, "We had to mitigate the risk."),
        Line(Speaker.Coach, "Nice. What happened next?"),
        Line(Speaker.User, "So we mitigate the risk, and I ended up working late, I ended up tired."),
    )

    private suspend fun finish(id: String = "p1") = speaking.finish(SpeakingState(id, startedAt, 20.minutes, AudioRoute.Earphones, "travel", talkTime = 20.minutes, transcript = transcript))

    private suspend fun row(id: String = "p1") = db.speakingDao().sessions().first().single { it.id == id }

    private suspend fun job(id: String = "p1") = OpicStore(db).jobs().first().single { it.kind == JobKind.Cards && it.targetId == id }

    private fun draft(kind: CardKind, better: String, errorType: ErrorType? = null) = CardDraft(kind, "I go to the beach", better, "뜻", errorType, "지난 주말")

    @Test
    fun `F17_4 끝낸 연습을 정리하면 카드를 저장하고 정리 끝으로 둔다`() = test {
        finish()
        writer.results += AnalysisResult.Success(CardBatch(listOf(draft(CardKind.Correction, "I went to the beach", ErrorType.Tense)), skippedErrors = 0))

        jobs.enqueueCards("p1")
        jobs.drain()

        assertEquals(CardStatus.Done.name, row().cardStatus)
        assertEquals(JobState.Succeeded, job().state)
        assertEquals(listOf("I went to the beach"), cards.cards().first().map { it.better })
    }

    @Test
    fun `F17_5 새 카드는 처음 봄 예이고 다음 복습은 연습을 끝낸 날의 다음 날 새벽 4시다`() = test {
        finish()
        writer.results += AnalysisResult.Success(CardBatch(listOf(draft(CardKind.Formulaic, "The thing is,")), skippedErrors = 0))

        jobs.enqueueCards("p1")
        jobs.drain()

        val card = cards.cards().first().single()
        assertTrue(card.firstSeen)
        assertEquals(Instant.parse("2026-11-19T19:00:00Z"), card.dueAt)
        assertEquals("p1", card.practiceId)
    }

    @Test
    fun `F17_5 정리가 끝나면 만든 카드 수와 넘긴 오류 수를 연습 기록에 적는다`() = test {
        finish()
        cards.save(ExpressionCard(kind = CardKind.UnknownExpression, said = "위험을 줄이다", better = "mitigate the risk", meaning = "위험을 줄이다", errorType = null, context = "c", createdAt = startedAt + 5.minutes, dueAt = now, practiceId = "p1"))
        writer.results += AnalysisResult.Success(CardBatch(listOf(draft(CardKind.Correction, "I went to the beach", ErrorType.Tense), draft(CardKind.Formulaic, "The thing is,")), skippedErrors = 3))

        jobs.enqueueCards("p1")
        jobs.drain()

        assertEquals(3 to 3, row().cardCount to row().skippedErrors)
    }

    @Test
    fun `F17_9 표현을 쓴 횟수는 시작 전 카드와 연습 중 들려준 표현을 세고 들려준 직후 따라 말한 것은 뺀다`() = test {
        finish()
        cards.save(ExpressionCard(kind = CardKind.Formulaic, said = "s", better = "I ended up", meaning = "m", errorType = null, context = "c", createdAt = startedAt - 1.minutes, dueAt = now))
        cards.save(ExpressionCard(kind = CardKind.UnknownExpression, said = "위험을 줄이다", better = "mitigate the risk", meaning = "m", errorType = null, context = "c", createdAt = startedAt + 5.minutes, dueAt = now, practiceId = "p1"))

        jobs.enqueueCards("p1")
        jobs.drain()

        // I ended up 3번, mitigate the risk 는 따라 말한 1번을 빼고 1번이다.
        assertEquals(4, row().expressionUses)
    }

    @Test
    fun `F17_4 실패하면 30초 2분 10분 뒤 다시 시도하고 그래도 안 되면 정리 실패로 둔다`() = test {
        finish()
        repeat(4) { writer.results += AnalysisResult.Failed(AnalysisError.Retryable) }
        jobs.enqueueCards("p1")
        val waits = mutableListOf<Duration>()

        repeat(3) {
            jobs.drain()
            assertEquals(CardStatus.Running.name, row().cardStatus)
            val wait = job().nextAttemptAt!! - now
            waits += wait
            now += wait
        }
        jobs.drain()

        assertEquals(listOf(30.seconds, 2.minutes, 10.minutes), waits)
        assertEquals(JobState.Failed, job().state)
        assertEquals(CardStatus.Failed.name, row().cardStatus)
    }

    @Test
    fun `F17_4 응답을 끝내 읽지 못해도 다시 시도한다`() = test {
        finish()
        writer.results += AnalysisResult.Failed(AnalysisError.Rejected)

        jobs.enqueueCards("p1")
        jobs.drain()

        assertEquals(JobState.RetryScheduled, job().state)
    }

    @Test
    fun `F17_4 앱을 켤 때 정리 전이거나 정리 실패인 연습을 다시 넣고 정리 끝인 연습은 두지 않는다`() = test {
        finish("pending")
        finish("failed")
        finish("done")
        speaking.finishCards("failed", CardStatus.Failed)
        speaking.finishCards("done", CardStatus.Done)

        jobs.recover()

        assertEquals(setOf("pending", "failed"), OpicStore(db).jobs().first().filter { it.kind == JobKind.Cards && it.state == JobState.Queued }.map { it.targetId }.toSet())
    }

    @Test
    fun `F17_4 녹음 전송 동의를 철회해도 연습 정리는 멈추지 않는다`() = test {
        finish()
        jobs.enqueueCards("p1")

        jobs.cancelAll()

        assertEquals(JobState.Queued, job().state)
    }

    @Test
    fun `F17_4 음성 녹음 전송 동의와 상관없이 정리한다`() = test {
        finish()

        jobs.enqueueCards("p1")
        jobs.drain()

        assertEquals(CardStatus.Done.name, row().cardStatus)
    }
}

/** 넣어 둔 결과를 차례로 돌려주고, 다 쓰면 카드 없음이다. */
private class FakeWriter : CardWriter {
    val results = ArrayDeque<AnalysisResult<CardBatch>>()

    override suspend fun write(transcript: List<Line>, existing: List<ExpressionCard>): AnalysisResult<CardBatch> = results.removeFirstOrNull() ?: AnalysisResult.Success(CardBatch(emptyList(), 0))
}

/** 연습 정리 테스트는 답변 분석과 회차 등급을 넣지 않는다. */
private object NoAnswers : AudioAnswerAnalyzer, Transcriber, TranscriptAnalyzer, SessionGrader {
    override suspend fun analyze(model: ModelInfo, prompt: Prompt, question: Question, recording: Recording): AnalysisResult<Feedback> = error("부르지 않는다")

    override suspend fun transcribe(recording: Recording): AnalysisResult<Transcript> = error("부르지 않는다")

    override suspend fun analyze(model: ModelInfo, prompt: Prompt, question: Question, transcript: Transcript): AnalysisResult<TextAnalysis> = error("부르지 않는다")

    override suspend fun grade(model: ModelInfo, prompt: Prompt, scale: GradeScale, recordings: List<Recording>): AnalysisResult<SessionGrade> = error("부르지 않는다")
}
