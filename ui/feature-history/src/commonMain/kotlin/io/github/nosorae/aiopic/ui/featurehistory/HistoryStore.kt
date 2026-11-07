package io.github.nosorae.aiopic.ui.featurehistory

import io.github.nosorae.aiopic.engine.answer.model.AnalysisJob
import io.github.nosorae.aiopic.engine.answer.model.Answer
import io.github.nosorae.aiopic.engine.answer.weakness.CorrectionFlag
import io.github.nosorae.aiopic.engine.session.model.ExamSession
import kotlinx.coroutines.flow.Flow

/** 기록이 읽는 저장소. 앱 층이 답변 저장소(OpicStore)를 그대로 이어 주고, 테스트는 메모리 가짜를 쓴다. 저장한 순서로 돌려준다. */
interface HistoryStore {
    fun sessions(): Flow<List<ExamSession>>

    fun answers(): Flow<List<Answer>>

    /** 리포트에서 "틀림"을 누른 교정. 약점 점수에서 뺀다(F8.2). */
    fun flags(): Flow<Set<CorrectionFlag>>

    /** 분석 작업 표(#205). 기다리는 연습 답변이 지금 무엇을 기다리는지 보인다. */
    fun jobs(): Flow<List<AnalysisJob>>

    /** 이미 본 것(#198). 약점 유형마다 지난번에 본 점수가 있다. 앱을 다시 켜도 남는다. */
    fun seen(): Flow<Map<String, Int>>

    suspend fun markSeen(key: String, value: Int)

    /** 코치 세션이 남긴 스크립트, 최근 것부터(F14.6). 질문 하나에 여럿일 수 있다. */
    fun scripts(): Flow<List<HistoryScript>>
}
