package io.github.nosorae.aiopic.ui.featurehistory

import io.github.nosorae.aiopic.engine.answer.model.AnalysisJob
import io.github.nosorae.aiopic.engine.answer.model.Answer
import io.github.nosorae.aiopic.engine.answer.weakness.CorrectionFlag
import io.github.nosorae.aiopic.engine.session.model.ExamSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class FakeHistoryStore(
    sessions: List<ExamSession> = emptyList(),
    answers: List<Answer> = emptyList(),
    /** 본 표시. 앱을 다시 켠 경우는 앞 저장소의 이 값만 넘겨 새로 만든다. */
    seen: Map<String, Int> = emptyMap(),
) : HistoryStore {
    val seen = MutableStateFlow(seen)
    val sessions = MutableStateFlow(sessions)
    val answers = MutableStateFlow(answers)
    val flags = MutableStateFlow(emptySet<CorrectionFlag>())
    val jobs = MutableStateFlow(emptyList<AnalysisJob>())
    val scripts = MutableStateFlow(emptyList<HistoryScript>())

    override fun sessions(): Flow<List<ExamSession>> = sessions

    override fun answers(): Flow<List<Answer>> = answers

    override fun flags(): Flow<Set<CorrectionFlag>> = flags

    override fun jobs(): Flow<List<AnalysisJob>> = jobs

    override fun seen(): Flow<Map<String, Int>> = seen

    override fun scripts(): Flow<List<HistoryScript>> = scripts

    override suspend fun markSeen(key: String, value: Int) {
        seen.update { it + (key to value) }
    }
}
