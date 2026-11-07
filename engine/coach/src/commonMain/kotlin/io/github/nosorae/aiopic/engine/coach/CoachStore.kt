package io.github.nosorae.aiopic.engine.coach

import io.github.nosorae.aiopic.engine.answer.store.CoachScriptRow
import io.github.nosorae.aiopic.engine.answer.store.CoachSessionRow
import io.github.nosorae.aiopic.engine.answer.store.OpicDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * 코치 세션을 앱 DB 에 저장한다. 세션 행은 바뀔 때마다 덮어쓰고, 받아쓰기, 도구 호출, 사용량, 단계 전환은 이벤트 표에 하나씩 붙인다.
 * 읽을 때는 이벤트를 [reduce] 로 다시 계산하고 세션 행 값을 덮어 앱이 죽기 전 상태를 그대로 되살린다.
 */
class CoachStore(db: OpicDatabase) {
    private val dao = db.coachDao()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun save(state: CoachState) = dao.save(
        CoachSessionRow(
            id = state.id,
            questionId = state.questionId,
            targetGrade = state.targetGrade,
            startedAt = state.startedAt.toEpochMilliseconds(),
            phase = state.phase.name,
            talkTimeMs = state.talkTime.inWholeMilliseconds,
            resumeHandle = state.resumeHandle,
            handleAt = state.handleAt?.toEpochMilliseconds(),
            status = state.status.name,
        ),
    )

    /** 다시 계산에 쓰는 일만 붙이고 나머지(시간, 일시정지, 끊김, 핸들)는 세션 행에 맡긴다. */
    suspend fun append(sessionId: String, phase: CoachPhase, event: CoachEvent) {
        val kind = when (event) {
            is CoachEvent.Heard, is CoachEvent.Rehearsed -> "transcript"
            is CoachEvent.ToolCalled -> "tool"
            is CoachEvent.Used -> "usage"
            CoachEvent.NextPhase -> "phase"
            else -> return
        }
        dao.append(sessionId, phase.name, kind, json.encodeToString<CoachEvent>(event))
    }

    suspend fun load(id: String): CoachState? {
        val row = dao.session(id) ?: return null
        val start = CoachState(row.id, row.questionId, row.targetGrade, Instant.fromEpochMilliseconds(row.startedAt))
        return dao.events(id).map { json.decodeFromString<CoachEvent>(it.json) }.fold(start, ::reduce).copy(
            // 30분 끝처럼 이벤트로 남기지 않은 단계 전환도 있어 단계와 상태는 행을 따른다.
            phase = CoachPhase.valueOf(row.phase),
            status = CoachStatus.valueOf(row.status),
            talkTime = row.talkTimeMs.milliseconds,
            resumeHandle = row.resumeHandle,
            handleAt = row.handleAt?.let(Instant::fromEpochMilliseconds),
        )
    }

    suspend fun save(script: CoachScript) = dao.save(
        CoachScriptRow(
            sessionId = script.sessionId,
            intro = script.intro,
            body = script.body,
            closing = script.closing,
            expressions = json.encodeToString(script.expressions),
            edited = script.edited,
            editedAt = script.editedAt?.toEpochMilliseconds(),
            promptVersion = script.promptVersion,
            model = script.model,
        ),
    )

    fun script(sessionId: String): Flow<CoachScript?> = dao.script(sessionId).map { row ->
        row?.let {
            CoachScript(
                sessionId = it.sessionId,
                intro = it.intro,
                body = it.body,
                closing = it.closing,
                expressions = json.decodeFromString(it.expressions),
                edited = it.edited,
                editedAt = it.editedAt?.let(Instant::fromEpochMilliseconds),
                promptVersion = it.promptVersion,
                model = it.model,
            )
        }
    }
}
