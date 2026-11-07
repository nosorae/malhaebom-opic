package io.github.nosorae.aiopic.ui.featurehistory

import io.github.nosorae.aiopic.engine.answer.model.AnalyzedBy
import io.github.nosorae.aiopic.engine.answer.model.AnswerAnalysis
import io.github.nosorae.aiopic.engine.answer.model.Feedback
import io.github.nosorae.aiopic.engine.answer.model.JobState
import io.github.nosorae.aiopic.engine.answer.model.Pronunciation
import io.github.nosorae.aiopic.engine.question.model.QuestionType
import io.github.nosorae.aiopic.engine.session.model.AnalysisError
import io.github.nosorae.aiopic.engine.session.model.ExamSession
import io.github.nosorae.aiopic.engine.session.model.ExamStatus
import io.github.nosorae.aiopic.engine.session.model.SessionGrade

/** 미리보기와 스크린샷 테스트가 같이 쓰는 고정 상태. 목록은 최근 것부터다. */
internal object HistorySamples {
    private const val FLASH = "Gemini 3.8 Flash"
    private const val LITE = "Gemini 3.5 Flash Lite"

    private fun session(id: String, low: String, high: String, status: ExamStatus = ExamStatus.Finished) = ExamSession(id = id, status = status, grade = SessionGrade(emptyList(), low, high, emptyList(), emptyList()))

    private fun scored(score: Int) = AnswerAnalysis.Done(
        AnalyzedBy("gemini", "flash", "v1"),
        Feedback("", emptyList(), emptyList(), true, 0, 0.0, emptyList(), null, Pronunciation.NotEvaluated, score),
    )

    val filled = HistoryUiState(
        loading = false,
        entries = listOf(
            HistoryPractice("p3", "롤플레이 문제 해결", null, AnswerAnalysis.Pending, JobState.Running),
            HistorySession(ExamSession(id = "s4", status = ExamStatus.Finished), listOf(FLASH), answered = 7, analyzed = 4, number = 4),
            HistoryPractice("p2", "과거 경험", FLASH, scored(72)),
            HistorySession(session("s3", "IH", "IH"), listOf(FLASH), number = 3),
            HistoryPractice("p1", "습관", null, AnswerAnalysis.Failed(AnalysisError.Retryable)),
            HistorySession(session("s2", "IM2", "IM3", ExamStatus.TimeUp), listOf(FLASH, LITE), number = 2),
            HistorySession(session("s1", "IM1", "IM2"), listOf(FLASH), number = 1),
        ),
        weaknesses = listOf(
            HistoryWeakness(QuestionType("roleplay"), "롤플레이 문제 해결", 48),
            HistoryWeakness(QuestionType("past"), "과거 경험", 61),
            HistoryWeakness(QuestionType("routine"), "습관", 77),
        ),
        grades = listOf("NL", "NM", "NH", "IL", "IM1", "IM2", "IM3", "IH", "AL"),
    )

    /** 새로 시작해 미완료로 끝낸 회차(#223). 답한 문항이 있으면 그 문항으로 등급이 나오고, 없으면 등급을 기다리지 않는다. */
    val abandoned = HistoryUiState(
        loading = false,
        entries = listOf(
            HistorySession(ExamSession(id = "s3", status = ExamStatus.Abandoned), emptyList(), number = 3),
            HistorySession(session("s2", "IM1", "IM2", ExamStatus.Abandoned), listOf(FLASH), answered = 4, analyzed = 4, number = 2),
            HistorySession(session("s1", "IM1", "IM2"), listOf(FLASH), number = 1),
        ),
        grades = filled.grades,
    )

    val empty = HistoryUiState(loading = false)

    /** 같은 질문으로 만든 스크립트 둘과 만들지 못한 세션 하나가 맨 위에 있다(F14.6). */
    val withScripts = filled.copy(
        entries = listOf(
            HistoryScript("c2", "q1", "Tell me about a memorable trip you took recently.", "AL"),
            HistoryScript("c1", "q1", "Tell me about a memorable trip you took recently.", "IH"),
            HistoryScript("c0", "q2", "Describe your favorite park.", "IH", written = false),
        ) + filled.entries,
    )

    /** 지난번에 본 것보다 "과거 경험" 점수가 오른 약점 목록(#198). 축하가 화면 안에서 보이게 기록 줄은 비운다. */
    val weaknessRose = HistoryUiState(
        loading = false,
        weaknesses = listOf(HistoryWeakness(QuestionType("roleplay"), "롤플레이 문제 해결", 48), HistoryWeakness(QuestionType("past"), "과거 경험", 61, rose = true), HistoryWeakness(QuestionType("routine"), "습관", 77)),
    )
}
