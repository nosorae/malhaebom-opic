package io.github.nosorae.aiopic.engine.speaking

import io.github.nosorae.aiopic.engine.answer.store.OpicDatabase
import io.github.nosorae.aiopic.engine.answer.store.SpeakingActiveRow
import io.github.nosorae.aiopic.engine.answer.store.SpeakingSessionRow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** 정리 전(F17.4). 연습 정리 작업이 정리 중, 정리 끝, 정리 실패로 바꾼다. */
const val CARD_STATUS_PENDING = "Pending"

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
            cardStatus = CARD_STATUS_PENDING,
            usage = json.encodeToString(state.usage),
            costUsd = state.usage.costUsd,
        ),
    )

    /** 주제마다 마지막 연습 시각, 연습 수, 그 연습의 첫 질문(F16.5). */
    suspend fun topics(): List<TopicHistory> = dao.topics().map {
        TopicHistory(it.topic, Instant.fromEpochMilliseconds(it.lastAt), it.count, json.decodeFromString<List<Asked>>(it.questions).firstOf(it.topic)?.text)
    }

    /** 모든 연습 기록의 다룬 질문. 무너진 기록이 많은 유형을 고를 때 쓴다(F16.6). */
    suspend fun pastQuestions(): List<Asked> = dao.sessions().first().flatMap { json.decodeFromString<List<Asked>>(it.questions) }
}
