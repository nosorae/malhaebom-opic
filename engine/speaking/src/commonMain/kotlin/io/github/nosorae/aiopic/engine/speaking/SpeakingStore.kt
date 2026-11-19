package io.github.nosorae.aiopic.engine.speaking

import io.github.nosorae.aiopic.engine.answer.store.OpicDatabase
import io.github.nosorae.aiopic.engine.answer.store.SpeakingActiveRow
import io.github.nosorae.aiopic.engine.answer.store.SpeakingSessionRow
import io.github.nosorae.aiopic.engine.review.ExpressionCard
import io.github.nosorae.aiopic.engine.review.toRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * 진행 중인 연습과 연습 기록을 앱 DB 에 저장한다(design.md 12.4). 진행 중인 연습은 응답마다 통째로 덮어쓰고(F16.16),
 * 끝내기는 그 줄을 지우고 연습 기록을 넣는 일을 한 트랜잭션으로 한다(F16.11). 기기에만 둔다(F17.8).
 */
class SpeakingStore(db: OpicDatabase) {
    private val dao = db.speakingDao()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun saveActive(state: SpeakingState) = dao.saveActive(
        SpeakingActiveRow(
            id = state.id,
            startedAt = state.startedAt.toEpochMilliseconds(),
            plannedMinutes = state.planned.inWholeMinutes.toInt(),
            audioRoute = state.route.name,
            phase = state.phase.name,
            status = state.status.name,
            pausedAt = state.pausedAt?.toEpochMilliseconds(),
            talkTimeMs = state.talkTime.inWholeMilliseconds,
            userTalkMs = state.userTalk.inWholeMilliseconds,
            coachTalkMs = state.coachTalk.inWholeMilliseconds,
            silenceCount = state.silenceCount,
            reconnects = state.reconnects,
            topic = state.topic,
            topicSwitches = state.topicSwitches,
            questions = json.encodeToString(state.questions),
            currentQuestion = state.current,
            transcript = json.encodeToString(state.transcript),
            savedCount = state.savedCount,
            resumeHandle = state.resumeHandle,
            handleAt = state.handleAt?.toEpochMilliseconds(),
            usage = json.encodeToString(state.usage),
            reviewCardIds = json.encodeToString(state.reviewCardIds),
            oralReviews = json.encodeToString(state.oralReviews),
        ),
    )

    /** 진행 중인 연습. 없으면 null 이다. */
    suspend fun active(): SpeakingState? = dao.active()?.let {
        SpeakingState(
            id = it.id,
            startedAt = Instant.fromEpochMilliseconds(it.startedAt),
            planned = it.plannedMinutes.minutes,
            route = AudioRoute.valueOf(it.audioRoute),
            topic = it.topic,
            reviewCardIds = json.decodeFromString(it.reviewCardIds),
            phase = SpeakingPhase.valueOf(it.phase),
            status = SpeakingStatus.valueOf(it.status),
            pausedAt = it.pausedAt?.let(Instant::fromEpochMilliseconds),
            talkTime = it.talkTimeMs.milliseconds,
            userTalk = it.userTalkMs.milliseconds,
            coachTalk = it.coachTalkMs.milliseconds,
            silenceCount = it.silenceCount,
            reconnects = it.reconnects,
            topicSwitches = it.topicSwitches,
            questions = json.decodeFromString(it.questions),
            current = it.currentQuestion,
            transcript = json.decodeFromString(it.transcript),
            savedCount = it.savedCount,
            resumeHandle = it.resumeHandle,
            handleAt = it.handleAt?.let(Instant::fromEpochMilliseconds),
            usage = json.decodeFromString(it.usage),
            oralReviews = json.decodeFromString(it.oralReviews),
        )
    }

    /** 진행 중인 줄을 지우고 정리 전 상태의 연습 기록을 넣는다(F16.11). 정리 작업 예약은 정리 작업 쪽이 한다. */
    suspend fun finish(state: SpeakingState) = dao.finish(
        SpeakingSessionRow(
            id = state.id,
            startedAt = state.startedAt.toEpochMilliseconds(),
            talkTimeMs = state.talkTime.inWholeMilliseconds,
            userTalkMs = state.userTalk.inWholeMilliseconds,
            coachTalkMs = state.coachTalk.inWholeMilliseconds,
            topic = state.topic,
            topicSwitches = state.topicSwitches,
            questions = json.encodeToString(state.questions),
            transcript = json.encodeToString(state.transcript),
            oralReviews = json.encodeToString(state.oralReviews),
            expressionUses = 0,
            cardCount = 0,
            skippedErrors = 0,
            reportCount = 0,
            cardStatus = CardStatus.Pending.name,
            usage = json.encodeToString(state.usage),
            costUsd = state.usage.costUsd,
        ),
    )

    /** 연습 기록, 최근 것부터. 연습 탭의 지난 연습 날짜와 연습 정리 화면이 읽는다(F16.1, F17.6). */
    fun sessions(): Flow<List<SpeakingRecord>> = dao.sessions().map { rows ->
        rows.map {
            SpeakingRecord(
                id = it.id,
                startedAt = Instant.fromEpochMilliseconds(it.startedAt),
                talkTime = it.talkTimeMs.milliseconds,
                userTalk = it.userTalkMs.milliseconds,
                coachTalk = it.coachTalkMs.milliseconds,
                questions = json.decodeFromString(it.questions),
                oralReviews = json.decodeFromString(it.oralReviews),
                expressionUses = it.expressionUses,
                cardCount = it.cardCount,
                reportCount = it.reportCount,
                cardStatus = CardStatus.valueOf(it.cardStatus),
            )
        }
    }

    /** 틀린 지적 신고 수를 하나 올린다(F17.7). 지운 연습이면 아무것도 하지 않는다. */
    suspend fun report(id: String) {
        val row = row(id) ?: return
        dao.save(row.copy(reportCount = row.reportCount + 1))
    }

    /** 말로 복습 판정을 바꾼 카드와 판정 목록을 한 트랜잭션으로 쓴다(F17.10). 지운 연습이면 아무것도 하지 않는다. */
    suspend fun saveJudgment(id: String, card: ExpressionCard, reviews: List<OralReview>) {
        val row = row(id) ?: return
        dao.finishCards(listOf(card.toRow()), row.copy(oralReviews = json.encodeToString(reviews)))
    }

    /** 주제마다 마지막 연습 시각, 연습 수, 그 연습의 첫 질문(F16.5). */
    suspend fun topics(): List<TopicHistory> = dao.topics().map {
        TopicHistory(it.topic, Instant.fromEpochMilliseconds(it.lastAt), it.count, json.decodeFromString<List<Asked>>(it.questions).firstOf(it.topic)?.text)
    }

    /**
     * 정리 상태를 바꾸고, 정리가 끝났으면 [cards] 를 저장하고 만든 카드 수, 넘긴 오류 수, 표현을 쓴 횟수를 적는다(F17.4, F17.5).
     * 정리 끝은 카드와 연습 기록을 한 트랜잭션으로 쓴다. 지운 연습이면 아무것도 하지 않는다.
     */
    suspend fun finishCards(id: String, status: CardStatus, cards: List<ExpressionCard> = emptyList(), cardCount: Int = 0, skippedErrors: Int = 0, expressionUses: Int = 0) {
        val row = row(id) ?: return
        if (status == CardStatus.Done) {
            dao.finishCards(cards.map { it.toRow() }, row.copy(cardStatus = status.name, cardCount = cardCount, skippedErrors = skippedErrors, expressionUses = expressionUses))
        } else {
            dao.save(row.copy(cardStatus = status.name))
        }
    }

    /** 그 연습에서 만든 카드 수. 정리 끝 알림이 쓴다(F17.5). 지운 연습이면 null 이다. */
    suspend fun cardCount(id: String): Int? = row(id)?.cardCount

    /** 정리 끝이 아닌 연습 id. 앱을 켤 때 정리를 다시 넣는다(F17.4). */
    suspend fun unfinishedCards(): List<String> = dao.sessions().first().filter { it.cardStatus != CardStatus.Done.name }.map { it.id }

    /** 정리할 연습의 시작 시각, 대화 시간, 대화 전문, 정리 상태. 지운 연습이면 null 이다. */
    internal suspend fun practice(id: String): Practice? = row(id)?.let {
        Practice(Instant.fromEpochMilliseconds(it.startedAt), it.talkTimeMs.milliseconds, json.decodeFromString(it.transcript), CardStatus.valueOf(it.cardStatus))
    }

    // ponytail: 연습 하나를 찾으려고 전체를 읽는다. 연습이 수천 개로 늘면 DAO 에 id 로 읽는 쿼리를 더한다.
    private suspend fun row(id: String) = dao.sessions().first().firstOrNull { it.id == id }

    /** 모든 연습 기록의 다룬 질문. 무너진 기록이 많은 유형을 고를 때 쓴다(F16.6). */
    suspend fun pastQuestions(): List<Asked> = dao.sessions().first().flatMap { json.decodeFromString<List<Asked>>(it.questions) }
}

/** 끝난 연습 하나(F17.6). 내 말 비율은 [userShare] 로 센다(F16.24). */
data class SpeakingRecord(
    val id: String,
    val startedAt: Instant,
    val talkTime: Duration,
    val userTalk: Duration,
    val coachTalk: Duration,
    val questions: List<Asked>,
    val oralReviews: List<OralReview>,
    val expressionUses: Int,
    val cardCount: Int,
    val reportCount: Int,
    val cardStatus: CardStatus,
) {
    /** 내가 말한 시간을 두 사람이 말한 시간의 합으로 나눈 값. 아무도 말하지 않았으면 0 이다. */
    val userShare: Double get() = (userTalk + coachTalk).takeIf { it.isPositive() }?.let { userTalk / it } ?: 0.0
}

/** 정리 작업이 읽는 연습 기록(F17.4). */
internal class Practice(val startedAt: Instant, val talkTime: Duration, val transcript: List<Line>, val cardStatus: CardStatus)
