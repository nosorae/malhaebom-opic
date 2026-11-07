package io.github.nosorae.aiopic.packs.opicen

import io.github.nosorae.aiopic.engine.question.model.Question
import io.github.nosorae.aiopic.engine.question.model.QuestionType
import io.github.nosorae.aiopic.engine.question.model.Survey
import io.github.nosorae.aiopic.engine.question.model.SurveyOption
import io.github.nosorae.aiopic.engine.question.model.SurveyQuestion
import io.github.nosorae.aiopic.engine.question.model.Topic
import io.github.nosorae.aiopic.engine.question.model.TopicGroup
import io.github.nosorae.aiopic.engine.question.pick.parseQuestions
import io.github.nosorae.aiopic.engine.session.model.ExamSpec
import io.github.nosorae.aiopic.engine.session.model.ExamStep
import io.github.nosorae.aiopic.engine.session.model.GradeScale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.MissingResourceException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** 영어 오픽 한 회의 순서와 등급. 엔진은 이 값을 데이터로만 받는다(F2.1). */
object OpicEn {
    private val surveySet = listOf(OpicQuestionType.Description, OpicQuestionType.Routine, OpicQuestionType.PastExperience)

    private fun ask(type: OpicQuestionType, minLevel: Int = 1) = ExamStep.Ask(type.type, minLevel)

    // ponytail: 수준 1~2 에서 빠지는 문항을 13~15번으로 잡았다. 실제 시험에서 어느 문항이 빠지는지 확인되면 minLevel 만 옮긴다.

    /**
     * 자기소개, 서베이 주제 세트 둘, 재조정, 세트 하나, 롤플레이 셋, 비교와 사회 이슈 순이다.
     * 수준 1~2 는 12문항이라 마지막 세 문항을 건너뛴다.
     */
    val examSpec = ExamSpec(
        timeLimit = 40.minutes,
        levelCount = 6,
        steps = listOf(ExamStep.Survey(minTopics = MIN_TOPICS), ExamStep.LevelSelect, ExamStep.Check) +
            ask(OpicQuestionType.SelfIntro) +
            (surveySet + surveySet).map { ask(it) } +
            ExamStep.Recalibrate +
            surveySet.map { ask(it) } +
            ask(OpicQuestionType.RoleplayAsk) +
            ask(OpicQuestionType.RoleplaySolve) +
            ask(OpicQuestionType.RoleplayExperience, minLevel = 3) +
            ask(OpicQuestionType.Comparison, minLevel = 3) +
            ask(OpicQuestionType.SocialIssue, minLevel = 3),
    )

    val gradeScale = GradeScale(OpicGrade.entries.map { it.name })

    /** 라이브 코치의 목표 등급으로 고를 수 있는 등급, 낮은 것부터(F10.2). */
    val targetGrades = listOf(OpicGrade.IM1, OpicGrade.IM2, OpicGrade.IM3, OpicGrade.IH, OpicGrade.AL).map { it.name }

    /** 목표 등급별 스크립트 길이(F14.2). 이 앱이 정한 연습 기준이며 ACTFL 값이 아니다. */
    val scriptLengths = mapOf(
        OpicGrade.IM1.name to ScriptLength(1.minutes, 1.minutes, 8..10),
        OpicGrade.IM2.name to ScriptLength(1.minutes, 1.minutes, 8..10),
        OpicGrade.IM3.name to ScriptLength(75.seconds, 75.seconds, 10..12),
        OpicGrade.IH.name to ScriptLength(90.seconds, 2.minutes, 12..16),
        OpicGrade.AL.name to ScriptLength(2.minutes, 150.seconds, 16..20),
    )

    /** 목표 등급을 고른 적이 없을 때 쓴다(F10.4). */
    val defaultTargetGrade = OpicGrade.IH.name

    /** Part 4 에서 합쳐서 고를 주제 수. 서베이 단계와 [survey] 가 같은 값을 쓴다. */
    private const val MIN_TOPICS = 12

    private fun question(id: String, text: String, vararg options: SurveyOption) = SurveyQuestion(id, text, options.toList())

    private fun option(id: String, label: String, vararg next: SurveyQuestion) = SurveyOption(id, label, next.toList())

    private fun group(id: String, title: String, min: Int, vararg topics: Pair<String, String>) = TopicGroup(id, title, min, topics.map { (topicId, label) -> Topic(topicId, label) })

    private val workPeriod = question(
        "work-period",
        "일한 지 얼마나 됐나요?",
        option("first-under-2-months", "2개월이 안 됐고 첫 직장이에요"),
        option("under-2-months", "2개월이 안 됐고 전에도 일했어요"),
        option("over-2-months", "2개월이 넘었어요"),
    )
    private val manager = question("manager", "다른 사람을 관리하는 자리인가요?", option("yes", "네"), option("no", "아니요"))
    private val employed = question("employed", "지금 일을 하고 있나요?", option("yes", "네", workPeriod, manager), option("no", "아니요"))
    private val courses = arrayOf(option("degree", "학위 과정 수업"), option("career", "일에 필요한 기술을 배우는 수업"), option("language", "어학 수업"))

    /**
     * 서베이(F2.2). 구성(Part 1~2 하위 질문 갈래, Part 3 거주, Part 4 네 묶음과 묶음별 최소, 합쳐서 12개 이상)은 실제 시험 구성을 따르고
     * 질문 문장은 이 앱의 말로 다시 썼다. Part 4 선택지는 짧은 활동 이름이라 실제 시험 튜토리얼 목록의 이름을 그대로 쓴다.
     * 주제 id 는 내장 문항 풀의 주제 id 와 같아야 그 주제 문항이 나온다(F6.2). 풀에 문항이 없는 주제는 같은 묶음 주제의 문항으로 대신한다.
     * 풀의 home, cafes, shopping, tv 는 서베이 밖 돌발 주제로 두고, 집에서 보내는 휴가는 따로 home-vacation 으로 둔다.
     */
    val survey = Survey(
        questions = listOf(
            question(
                "work",
                "지금 하는 일은 어느 쪽에 가깝나요?",
                option("company", "회사나 사업", employed),
                option("home-office", "집에서 하는 일이나 사업", employed),
                option("teacher", "가르치는 일", question("teach-at", "어디서 가르치나요?", option("college", "대학이나 그 이상"), option("school", "초중고"), option("adult", "성인 교육 기관")), employed),
                option("none", "일해 본 적 없어요"),
            ),
            question(
                "student",
                "지금 학생인가요?",
                option("yes", "네", question("course-now", "어떤 수업을 듣고 있나요?", *courses)),
                option("no", "아니요", question("course-last", "가장 최근에 들은 수업은 무엇인가요?", *courses, option("over-5-years", "수업을 들은 지 5년이 넘었어요"))),
            ),
            question(
                "housing",
                "지금 누구와 어디서 지내나요?",
                option("alone", "혼자 집이나 아파트에서"),
                option("roommates", "친구나 룸메이트와 집이나 아파트에서"),
                option("family", "가족과 집이나 아파트에서"),
                option("dormitory", "학교 기숙사에서"),
                option("barracks", "군대 숙소에서"),
            ),
        ),
        groups = listOf(
            group(
                "leisure", "쉬는 날에는 주로 무엇을 하나요?", 2,
                "movies" to "영화보기", "clubs" to "클럽/나이트클럽 가기", "performances" to "공연보기", "concerts" to "콘서트보기", "museums" to "박물관가기",
                "parks" to "공원가기", "camping" to "캠핑하기", "beach" to "해변가기", "watching-sports" to "스포츠 관람", "home-improvement" to "주거 개선",
            ),
            group(
                "hobbies", "즐겨 하는 취미가 있나요?", 1,
                "reading-to-children" to "아이에게 책 읽어주기", "music" to "음악 감상하기", "instrument" to "악기 연주하기", "singing" to "혼자 노래부르거나 합창하기",
                "dancing" to "춤추기", "writing" to "글쓰기(편지, 단문, 시 등)", "drawing" to "그림 그리기", "cooking" to "요리하기", "pets" to "애완동물 기르기",
            ),
            group(
                "sports", "어떤 운동을 하나요?", 1,
                "basketball" to "농구", "baseball" to "야구/소프트볼", "soccer" to "축구", "american-football" to "미식축구", "hockey" to "하키",
                "cricket" to "크리켓", "golf" to "골프", "volleyball" to "배구", "tennis" to "테니스", "badminton" to "배드민턴", "table-tennis" to "탁구",
                "swimming" to "수영", "cycling" to "자전거", "skiing" to "스키/스노우보드", "ice-skating" to "아이스 스케이트", "jogging" to "조깅", "walking" to "걷기",
                "yoga" to "요가", "hiking" to "하이킹/트레킹", "fishing" to "낚시", "gym" to "헬스", "no-exercise" to "운동을 전혀 하지 않음",
            ),
            group(
                "trips",
                "휴가나 출장은 어떻게 보내나요?",
                1,
                "business-trip" to "국내출장",
                "overseas-business-trip" to "해외출장",
                "home-vacation" to "집에서 보내는 휴가",
                "domestic-travel" to "국내 여행",
                "overseas-travel" to "해외 여행",
            ),
        ),
        minTopics = MIN_TOPICS,
    )

    /**
     * 모델이 만든 질문에서 서베이 밖 주제를 몇 개까지 받는지(F6.1). 출제 프롬프트 규칙대로 자기소개(self), 서베이 밖 세트 하나,
     * 롤플레이 상황, 비교와 사회 이슈 주제가 서베이 밖일 수 있다. 넘으면 내장 문항으로 고른다(F6.2).
     */
    const val MAX_OUTSIDE_TOPICS = 4

    /** 내장 문항 풀(F6.2). 앱이 출제할 때마다 읽는다. */
    suspend fun questionPool(): List<Question> = parseQuestions(Res.readBytes("files/questions/pool.json").decodeToString())

    /**
     * 내장 문항 하나를 Gemini 음성으로 미리 읽어 둔 파일(모노 AAC m4a, #161). 없으면 null 이고 앱이 기기 음성으로 읽는다.
     * 파일은 `scripts/make_question_voices.py` 가 문항 id 이름으로 만든다.
     */
    suspend fun questionVoice(id: String): ByteArray? = try {
        Res.readBytes("files/voices/$id.m4a")
    } catch (_: MissingResourceException) {
        null
    }
}

/** 말하는 시간과 문장 수. 시간이 "안팎" 하나면 [shortest] 와 [longest] 가 같다. */
data class ScriptLength(val shortest: Duration, val longest: Duration, val sentences: IntRange)

/** 오픽 등급, 낮은 것부터. 회차 등급 응답 스키마가 이 값만 고르게 한다(F5.3). */
@Serializable
enum class OpicGrade { NL, NM, NH, IL, IM1, IM2, IM3, IH, AL }

/** 오픽 문항 유형. JSON 이름이 엔진의 [QuestionType] id 이고 출제 응답 스키마가 이 값만 고르게 한다(F6.1). */
@Serializable
enum class OpicQuestionType {
    @SerialName("self-intro")
    SelfIntro,

    @SerialName("description")
    Description,

    @SerialName("routine")
    Routine,

    @SerialName("past-experience")
    PastExperience,

    /** 상황 속 상대에게 질문을 해서 정보를 얻는다. */
    @SerialName("roleplay-ask")
    RoleplayAsk,

    /** 문제 상황을 설명하고 대안을 낸다. */
    @SerialName("roleplay-solve")
    RoleplaySolve,

    @SerialName("roleplay-experience")
    RoleplayExperience,

    @SerialName("comparison")
    Comparison,

    @SerialName("social-issue")
    SocialIssue,
    ;

    val type: QuestionType get() = QuestionType(serializer().descriptor.getElementName(ordinal))
}
