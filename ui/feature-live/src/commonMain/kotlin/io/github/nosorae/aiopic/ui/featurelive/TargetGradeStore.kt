package io.github.nosorae.aiopic.ui.featurelive

import kotlinx.coroutines.flow.StateFlow

/**
 * 라이브 코치의 목표 등급. 사용자 전역 설정 하나다(F10.3). 등급 목록과 기본값은 시험 팩이 정하고, 앱 층이 플랫폼 설정 저장소로 구현한다.
 * 시작 시트와 설정 화면이 같은 값을 구독해서 한쪽에서 바꾸면 다른 쪽도 바로 따라간다.
 */
interface TargetGradeStore {
    /** 고를 수 있는 목표 등급, 낮은 것부터(F10.2). */
    val targetGrades: List<String>

    /** 지금 목표 등급. 고른 적이 없으면 시험 팩의 기본값이다(F10.4). */
    val targetGrade: StateFlow<String>

    suspend fun setTargetGrade(grade: String)
}
