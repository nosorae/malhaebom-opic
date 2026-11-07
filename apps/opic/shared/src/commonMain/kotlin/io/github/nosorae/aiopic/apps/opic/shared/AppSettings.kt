package io.github.nosorae.aiopic.apps.opic.shared

import io.github.nosorae.aiopic.engine.question.model.Question
import io.github.nosorae.aiopic.engine.question.model.SurveyAnswers
import io.github.nosorae.aiopic.libs.haptics.api.Haptics
import io.github.nosorae.aiopic.packs.opicen.OpicEn
import io.github.nosorae.aiopic.ui.featureexam.SurveyStore
import io.github.nosorae.aiopic.ui.featurelive.TargetGradeStore
import io.github.nosorae.aiopic.ui.featureonboarding.consent.VoiceConsent
import io.github.nosorae.aiopic.ui.featuresettings.haptics.HapticsStore
import io.github.nosorae.aiopic.ui.featuresettings.model.ModelSelectionStore
import io.github.nosorae.aiopic.ui.featuresettings.practice.AnswerTimerStore
import io.github.nosorae.aiopic.ui.featuresettings.practice.QuestionFileStore
import io.github.nosorae.aiopic.ui.featuresettings.theme.ThemeMode
import io.github.nosorae.aiopic.ui.featuresettings.theme.ThemeModeStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * 설정 값(화면 모드, 고른 분석 모델, 음성 전송 동의, 답변 타이머, 불러온 문항 파일, 진동, 서베이 답, 질문 음성, 코치 목표 등급)을 플랫폼 설정 저장소에 둔다.
 * 플랫폼은 문자열 읽기와 쓰기 두 함수만 넘긴다(Android SharedPreferences, iOS NSUserDefaults). 키가 아니라서 암호화하지 않는다.
 */
class AppSettings(private val read: (String) -> String?, private val write: (String, String) -> Unit) :
    ThemeModeStore,
    ModelSelectionStore,
    VoiceConsent,
    AnswerTimerStore,
    QuestionFileStore,
    HapticsStore,
    SurveyStore,
    TargetGradeStore {
    private val mode = MutableStateFlow(ThemeMode.entries.firstOrNull { it.name == read(THEME_MODE) } ?: ThemeMode.System)
    override val themeMode: StateFlow<ThemeMode> = mode.asStateFlow()

    override suspend fun setThemeMode(mode: ThemeMode) {
        write(THEME_MODE, mode.name)
        this.mode.value = mode
    }

    override val targetGrades: List<String> = OpicEn.targetGrades

    // 목록에 없는 값(팩이 등급을 바꾼 뒤 남은 값)은 고른 적이 없는 것으로 본다.
    private val grade = MutableStateFlow(read(TARGET_GRADE)?.takeIf { it in targetGrades } ?: OpicEn.defaultTargetGrade)
    override val targetGrade: StateFlow<String> = grade.asStateFlow()

    override suspend fun setTargetGrade(grade: String) {
        write(TARGET_GRADE, grade)
        this.grade.value = grade
    }

    override suspend fun selectedModelId(): String? = read(MODEL_ID)

    override suspend fun selectModel(id: String) = write(MODEL_ID, id)

    /** 고른 모델이 내려가 분석이 실패했을 때 부른다(F5.8). 기본 모델로 바꾸고, 설정 화면이 알릴 수 있게 원래 모델 id 를 남긴다. */
    suspend fun fallBack(from: String, to: String) {
        write(FELL_BACK_FROM, from)
        selectModel(to)
    }

    override suspend fun takeFellBackFrom(): String? = read(FELL_BACK_FROM)?.ifEmpty { null }?.also { write(FELL_BACK_FROM, "") }

    override suspend fun isGiven(): Boolean = read(VOICE_CONSENT) == "true"

    override suspend fun give() = write(VOICE_CONSENT, "true")

    override suspend fun revoke() = write(VOICE_CONSENT, "false")

    /** 시험 화면을 조립할 때 바로 읽는다(F2.7). 기본은 꺼짐이다. */
    val answerTimer: Boolean get() = read(ANSWER_TIMER) == "true"

    override suspend fun answerTimerOn(): Boolean = answerTimer

    override suspend fun setAnswerTimerOn(on: Boolean) = write(ANSWER_TIMER, on.toString())

    override suspend fun hapticsOn(): Boolean = read(HAPTICS) != "false"

    override suspend fun setHapticsOn(on: Boolean) = write(HAPTICS, on.toString())

    /** 플랫폼 진동에 설정의 켜기/끄기를 씌운다. 낼 때마다 읽어서 설정을 바꾸는 즉시 따른다. 기본은 켬이다. */
    fun haptics(platform: Haptics): Haptics = Haptics { if (read(HAPTICS) != "false") platform.play(it) }

    /** 질문을 자연스러운 음성으로 읽는다(#161). 읽을 때마다 읽어서 설정을 바꾸는 즉시 따른다. 기본은 켬이다. */
    val naturalVoiceOn: Boolean get() = read(NATURAL_VOICE) != "false"

    fun setNaturalVoiceOn(on: Boolean) = write(NATURAL_VOICE, on.toString())

    // ponytail: 문항 파일을 설정 저장소에 문자열 하나로 둔다. 몇십 KB 까지는 괜찮고, 파일이 MB 단위로 커지면 앱 폴더 파일로 옮긴다.
    override suspend fun questionFile(): String? = read(QUESTION_FILE)

    override suspend fun saveQuestionFile(json: String) = write(QUESTION_FILE, json)

    /** 회차 하나에 낸 질문(F6.1, F6.2). 모델 출제는 부를 때마다 결과가 달라서, 이어 볼 때 같은 질문이 나오게 처음 고른 목록을 남긴다(F3.3). */
    // ponytail: 회차마다 몇 KB 를 설정 저장소에 쌓고 지우지 않는다. 회차가 수백 개가 되면 회차 행(engine/session)으로 옮긴다.
    fun examQuestions(sessionId: String): List<Question>? = read(EXAM_QUESTIONS + sessionId)?.let { Json.decodeFromString<List<Question>>(it) }

    fun saveExamQuestions(sessionId: String, questions: List<Question>) = write(EXAM_QUESTIONS + sessionId, Json.encodeToString(questions))

    override suspend fun surveyAnswers(): SurveyAnswers? = read(SURVEY_ANSWERS)?.let { Json.decodeFromString<SurveyAnswers>(it) }

    override suspend fun saveSurveyAnswers(answers: SurveyAnswers) = write(SURVEY_ANSWERS, Json.encodeToString(answers))

    /** 첫 분석을 시작할 때 알림 권한을 한 번 물었는가(#229). 거절해도 다시 묻지 않는다. */
    var notificationsAsked: Boolean
        get() = read(NOTIFICATIONS_ASKED) == "true"
        set(value) = write(NOTIFICATIONS_ASKED, value.toString())

    /** 디버깅 화면의 설정 패널. 문항 파일은 수십 KB 라 길이만 보인다. 회차별 출제 목록은 DB 패널의 회차로 찾는다. */
    fun debugRows(): List<Pair<String, String>> = listOf(THEME_MODE, MODEL_ID, FELL_BACK_FROM, VOICE_CONSENT, ANSWER_TIMER, HAPTICS, TARGET_GRADE).map { it to (read(it) ?: "(none)") } +
        (QUESTION_FILE to (read(QUESTION_FILE)?.let { "${it.length} chars" } ?: "(none)"))

    companion object {
        private const val THEME_MODE = "theme_mode"
        private const val MODEL_ID = "model_id"
        private const val FELL_BACK_FROM = "model_fell_back_from"
        private const val VOICE_CONSENT = "voice_consent"
        private const val ANSWER_TIMER = "answer_timer"
        private const val QUESTION_FILE = "question_file"
        private const val HAPTICS = "haptics"
        private const val NATURAL_VOICE = "natural_voice"
        private const val EXAM_QUESTIONS = "exam_questions_"
        private const val SURVEY_ANSWERS = "survey_answers"
        private const val NOTIFICATIONS_ASKED = "notifications_asked"
        private const val TARGET_GRADE = "target_grade"

        /** 앱 틀 테스트처럼 기기에 남길 필요가 없을 때 쓴다. */
        fun inMemory(): AppSettings {
            val values = mutableMapOf<String, String>()
            return AppSettings(read = { values[it] }, write = { key, value -> values[key] = value })
        }
    }
}
