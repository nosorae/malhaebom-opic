package io.github.nosorae.aiopic.engine.coach

import io.github.nosorae.aiopic.engine.answer.store.CoachScriptRow
import io.github.nosorae.aiopic.engine.answer.store.CoachSessionRow
import io.github.nosorae.aiopic.engine.answer.store.OpicDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
            question = state.question,
            targetGrade = state.targetGrade,
            startedAt = state.startedAt.toEpochMilliseconds(),
            phase = state.phase.name,
            talkTimeMs = state.talkTime.inWholeMilliseconds,
            resumeHandle = state.resumeHandle,
            handleAt = state.handleAt?.toEpochMilliseconds(),
            status = state.status.name,
        ),
    )

    /** 다시 계산에 쓰는 일(사용자가 고친 메모 포함)만 붙이고 나머지(시간, 일시정지, 끊김, 핸들)는 세션 행에 맡긴다. */
    suspend fun append(sessionId: String, event: CoachEvent) {
        if (event is CoachEvent.TimePassed || event is CoachEvent.Paused || event is CoachEvent.Resumed || event is CoachEvent.Dropped || event is CoachEvent.HandleReceived) return
        dao.append(sessionId, json.encodeToString<CoachEvent>(event))
    }

    suspend fun load(id: String): CoachState? {
        val row = dao.session(id) ?: return null
        return dao.events(id).map { json.decodeFromString<CoachEvent>(it.json) }.fold(row.start(), ::reduce).copy(
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
            intro = script.original.intro,
            body = script.original.body,
            closing = script.original.closing,
            expressions = json.encodeToString(script.expressions),
            edited = script.edited?.let { json.encodeToString(it) },
            editedAt = script.editedAt?.toEpochMilliseconds(),
            promptVersion = script.promptVersion,
            model = script.model,
        ),
    )

    /**
     * 모든 세션, 최근 것부터(F14.6, F11.8). 둘째 값은 스크립트를 만들었는지다. 끝났는데 만들지 못한 세션도 내서 기록 탭에서 다시 만들고,
     * 진행 중인 세션은 기록 탭과 시작 시트에서 이어 간다. 세션은 질문 id 와 글, 목표 등급, 시작 시각, 단계, 상태만 채우고 받아쓰기와 메모는 비어 있다.
     * 대화 중에는 세션 행이 1초마다 저장되지만 이 값들은 그대로라 같은 목록은 다시 내지 않는다.
     */
    fun sessions(): Flow<List<Pair<CoachState, Boolean>>> = combine(dao.sessions(), dao.scriptIds()) { rows, ids ->
        val written = ids.toSet()
        rows.map { it.start().copy(phase = CoachPhase.valueOf(it.phase), status = CoachStatus.valueOf(it.status)) to (it.id in written) }
    }.distinctUntilChanged()

    private fun CoachSessionRow.start() = CoachState(id, questionId, question, targetGrade, Instant.fromEpochMilliseconds(startedAt))

    fun script(sessionId: String): Flow<CoachScript?> = dao.script(sessionId).map { row ->
        row?.let {
            CoachScript(
                sessionId = it.sessionId,
                original = ScriptParts(it.intro, it.body, it.closing),
                expressions = json.decodeFromString(it.expressions),
                edited = it.edited?.let { edited -> json.decodeFromString<ScriptParts>(edited) },
                editedAt = it.editedAt?.let(Instant::fromEpochMilliseconds),
                promptVersion = it.promptVersion,
                model = it.model,
            )
        }
    }
}
