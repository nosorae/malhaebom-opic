package io.github.nosorae.aiopic.packs.opicen

import io.github.nosorae.aiopic.engine.answer.model.ContentFix
import io.github.nosorae.aiopic.engine.answer.model.UnscorableReason
import io.github.nosorae.aiopic.engine.answer.model.Upgrade
import kotlinx.serialization.Serializable

/**
 * 답변 분석 응답. 이 클래스가 [OpicPrompt.Answer] 의 스키마다. 시간은 모델이 쓰기 쉬운 초 단위 숫자로 받고
 * 도메인의 `Feedback` 으로 옮기는 일은 분석 어댑터가 한다.
 */
@Serializable
data class AnswerResponse(
    val transcript: String,
    val corrections: List<CorrectionResponse>,
    val upgrades: List<Upgrade>,
    val tenseConsistent: Boolean,
    val sentenceCount: Int,
    val wordsPerMinute: Double,
    val pauses: List<PauseSeconds>,
    /** 롤플레이 문항에서만 온다. */
    val roleplayQuestionCount: Int? = null,
    val pronunciation: String,
    val score: Int,
    /** 내용 교정(#217). */
    val contentFixes: List<ContentFix>,
    /** 표현과 내용을 고친 최종 영어 답변(#217). */
    val finalScript: String,
    /** 평가할 수 없을 때만 온다(F5.5). */
    val unscorableReason: UnscorableReason? = null,
)

/**
 * 교정 하나. 도메인 `Correction` 은 옛 답변 때문에 [deducted] 가 null 일 수 있지만, 스키마에서는 모델이 꼭 채우게 한다(F8.2, ADR 0027).
 */
@Serializable
data class CorrectionResponse(val original: String, val corrected: String, val deducted: Int)

@Serializable
data class PauseSeconds(val startSeconds: Double, val lengthSeconds: Double)

/** 회차 등급 응답(F5.3). 체크리스트 결과와 등급 범위, 근거, 무너진 문항 번호다. */
@Serializable
data class SessionGradeResponse(
    val checks: List<ChecklistAnswer>,
    val low: OpicGrade,
    val high: OpicGrade,
    val reasons: List<String>,
    val brokeAt: List<Int>,
)

/** 체크리스트 문장 하나의 예/아니오. [id] 는 프롬프트의 `C1` 같은 번호다. */
@Serializable
data class ChecklistAnswer(val id: String, val met: Boolean)

/** 출제 응답(F6.1). */
@Serializable
data class QuestionsResponse(val questions: List<GeneratedQuestion>)

@Serializable
data class GeneratedQuestion(val number: Int, val type: OpicQuestionType, val topic: String, val text: String)

/** 스크립트 응답(F14.1, F14.4). 만들기와 다듬기가 같은 모양이다. [usedExpressions] 는 쓴 표현 카드의 english 글 그대로다. */
@Serializable
data class ScriptResponse(val intro: String, val body: String, val closing: String, val usedExpressions: List<String>)
