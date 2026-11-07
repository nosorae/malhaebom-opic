package io.github.nosorae.aiopic.packs.opicen

import io.github.nosorae.aiopic.engine.session.model.ExamStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class OpicEnTest {
    private val steps = OpicEn.examSpec.steps

    private fun askCount(level: Int) = steps.count { it is ExamStep.Ask && it.minLevel <= level }

    @Test
    fun `F2_1 서베이와 수준 선택과 점검 다음에 7문항과 재조정과 8문항 순서다`() {
        val shape = steps.map { if (it is ExamStep.Ask) "ask" else it::class.simpleName }

        assertEquals(listOf("Survey", "LevelSelect", "Check") + List(7) { "ask" } + "Recalibrate" + List(8) { "ask" }, shape)
    }

    @Test
    fun `F2_1 수준 1과 2 는 12문항이다`() {
        assertEquals(listOf(12, 12), (1..2).map(::askCount))
    }

    @Test
    fun `F2_1 수준 3부터 6 은 15문항이다`() {
        assertEquals(List(4) { 15 }, (3..6).map(::askCount))
    }

    @Test
    fun `F2_2 서베이는 12개 미만이면 다음으로 못 간다`() {
        assertEquals(ExamStep.Survey(minTopics = 12), steps.first())
    }

    @Test
    fun `F2_2 Part 1부터 3 은 하나만 고르는 질문이고 일과 학생 여부는 고른 답에 따라 하위 질문이 이어진다`() {
        val survey = OpicEn.survey
        assertEquals(listOf("work", "student", "housing"), survey.visibleQuestions(emptyMap()).map { it.id })
        assertEquals(
            listOf("work", "employed", "work-period", "manager", "student", "course-now", "housing"),
            survey.visibleQuestions(mapOf("work" to "company", "employed" to "yes", "student" to "yes")).map { it.id },
        )
        assertEquals(listOf("work", "teach-at", "employed", "student", "course-last", "housing"), survey.visibleQuestions(mapOf("work" to "teacher", "student" to "no")).map { it.id })
        assertEquals(5, survey.questions.last().options.size)
    }

    @Test
    fun `F2_2 Part 4 는 네 묶음 46개이고 묶음별 최소는 여가 2 취미 1 운동 1 휴가 1 이다`() {
        val groups = OpicEn.survey.groups
        assertEquals(listOf(10, 9, 22, 5), groups.map { it.topics.size })
        assertEquals(listOf(2, 1, 1, 1), groups.map { it.min })
        assertEquals(46, groups.flatMap { it.topics }.map { it.id }.toSet().size, "주제 id 가 겹치지 않는다")
    }

    @Test
    fun `F2_2 Part 4 선택지는 실제 시험 튜토리얼의 활동 이름 그대로다`() {
        assertEquals(
            listOf(
                listOf("영화보기", "클럽/나이트클럽 가기", "공연보기", "콘서트보기", "박물관가기", "공원가기", "캠핑하기", "해변가기", "스포츠 관람", "주거 개선"),
                listOf("아이에게 책 읽어주기", "음악 감상하기", "악기 연주하기", "혼자 노래부르거나 합창하기", "춤추기", "글쓰기(편지, 단문, 시 등)", "그림 그리기", "요리하기", "애완동물 기르기"),
                listOf(
                    "농구", "야구/소프트볼", "축구", "미식축구", "하키", "크리켓", "골프", "배구", "테니스", "배드민턴", "탁구", "수영", "자전거", "스키/스노우보드",
                    "아이스 스케이트", "조깅", "걷기", "요가", "하이킹/트레킹", "낚시", "헬스", "운동을 전혀 하지 않음",
                ),
                listOf("국내출장", "해외출장", "집에서 보내는 휴가", "국내 여행", "해외 여행"),
            ),
            OpicEn.survey.groups.map { group -> group.topics.map { it.label } },
        )
        assertTrue(OpicEn.survey.groups.flatMap { it.topics }.all { it.id.matches(Regex("[a-z]+(-[a-z]+)*")) }, "주제 id 는 영어 낱말이다")
    }

    @Test
    fun `F2_2 합쳐서 12개 미만이거나 묶음 최소를 못 채우면 받지 않는다`() {
        val (leisure, hobbies, sports, trips) = OpicEn.survey.groups.map { group -> group.topics.map { it.id } }
        val enough = leisure.take(5) + hobbies.take(3) + sports.take(3) + trips.take(1)

        assertTrue(OpicEn.survey.accepts(enough))
        assertFalse(OpicEn.survey.accepts(enough.drop(1)), "11개")
        assertFalse(OpicEn.survey.accepts(leisure + sports.take(2)), "취미와 휴가가 0개")
    }

    @Test
    fun `시험 시간은 40분이고 수준은 6개다`() {
        assertEquals(40.minutes to 6, OpicEn.examSpec.timeLimit to OpicEn.examSpec.levelCount)
    }

    @Test
    fun `1번은 자기소개이고 11번부터 13번은 롤플레이다`() {
        val asks = steps.filterIsInstance<ExamStep.Ask>().map { it.questionType }

        assertEquals(OpicQuestionType.SelfIntro.type, asks[0])
        assertEquals(listOf(OpicQuestionType.RoleplayAsk, OpicQuestionType.RoleplaySolve, OpicQuestionType.RoleplayExperience).map { it.type }, asks.subList(10, 13))
    }

    @Test
    fun `F5_3 등급은 NL 부터 AL 까지 9개다`() {
        assertEquals(listOf("NL", "NM", "NH", "IL", "IM1", "IM2", "IM3", "IH", "AL"), OpicEn.gradeScale.grades)
    }

    @Test
    fun `F10_2 F10_4 목표 등급은 IM1 부터 AL 까지 다섯이고 기본은 IH 다`() {
        assertEquals(listOf("IM1", "IM2", "IM3", "IH", "AL"), OpicEn.targetGrades)
        assertEquals("IH", OpicEn.defaultTargetGrade)
    }
}
