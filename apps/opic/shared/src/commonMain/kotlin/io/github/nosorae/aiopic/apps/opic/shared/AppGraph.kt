package io.github.nosorae.aiopic.apps.opic.shared

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraphFactory
import io.github.nosorae.aiopic.apps.opic.shared.data.StoreExamRepository
import io.github.nosorae.aiopic.apps.opic.shared.data.StorePracticeRepository
import io.github.nosorae.aiopic.apps.opic.shared.data.StoreReads
import io.github.nosorae.aiopic.engine.aigemini.GeminiAnalyzer
import io.github.nosorae.aiopic.engine.aigemini.GeminiLiveTransport
import io.github.nosorae.aiopic.engine.aigemini.GeminiModels
import io.github.nosorae.aiopic.engine.aigemini.GeminiQuestionWriter
import io.github.nosorae.aiopic.engine.aigemini.GeminiTranscriber
import io.github.nosorae.aiopic.engine.aigemini.GeminiScriptWriter
import io.github.nosorae.aiopic.engine.answer.analysis.AnalysisJobs
import io.github.nosorae.aiopic.engine.answer.analysis.AnalysisRequest
import io.github.nosorae.aiopic.engine.answer.analysis.GradeRequest
import io.github.nosorae.aiopic.engine.answer.check.choose
import io.github.nosorae.aiopic.engine.answer.model.Answer
import io.github.nosorae.aiopic.engine.answer.model.Feedback
import io.github.nosorae.aiopic.engine.answer.model.ModelInfo
import io.github.nosorae.aiopic.engine.answer.model.UnscorableReason
import io.github.nosorae.aiopic.engine.answer.port.AnalysisResult
import io.github.nosorae.aiopic.engine.answer.port.AudioAnswerAnalyzer
import io.github.nosorae.aiopic.engine.answer.port.ModelCatalog
import io.github.nosorae.aiopic.engine.answer.port.Prompt
import io.github.nosorae.aiopic.engine.answer.port.Recording
import io.github.nosorae.aiopic.engine.answer.port.TextAnalysis
import io.github.nosorae.aiopic.engine.answer.port.Transcriber
import io.github.nosorae.aiopic.engine.answer.port.Transcript
import io.github.nosorae.aiopic.engine.answer.port.TranscriptAnalyzer
import io.github.nosorae.aiopic.engine.answer.route.AnswerRouter
import io.github.nosorae.aiopic.engine.answer.store.OpicDatabase
import io.github.nosorae.aiopic.engine.answer.store.OpicStore
import io.github.nosorae.aiopic.engine.answer.weakness.weaknessScores
import io.github.nosorae.aiopic.engine.coach.CoachStore
import io.github.nosorae.aiopic.engine.coach.LiveCoach
import io.github.nosorae.aiopic.engine.coach.ScriptLength
import io.github.nosorae.aiopic.engine.coach.ScriptWriter
import io.github.nosorae.aiopic.engine.question.model.Question
import io.github.nosorae.aiopic.engine.question.model.QuestionType
import io.github.nosorae.aiopic.engine.question.pick.parseQuestions
import io.github.nosorae.aiopic.engine.session.model.AnalysisError
import io.github.nosorae.aiopic.engine.session.model.ExamSession
import io.github.nosorae.aiopic.engine.session.model.ExamSpec
import io.github.nosorae.aiopic.engine.session.model.GradeScale
import io.github.nosorae.aiopic.libs.debug.DebugLevel
import io.github.nosorae.aiopic.libs.debug.DebugTools
import io.github.nosorae.aiopic.libs.duplexaudio.api.DuplexAudio
import io.github.nosorae.aiopic.libs.keyvault.api.KeyVault
import io.github.nosorae.aiopic.libs.llmgemini.GeminiClient
import io.github.nosorae.aiopic.libs.llmgemini.api.LlmRequest
import io.github.nosorae.aiopic.libs.llmgemini.api.LlmResult
import io.github.nosorae.aiopic.libs.recorder.api.Recorder
import io.github.nosorae.aiopic.libs.timer.api.MonotonicTimer
import io.github.nosorae.aiopic.libs.timer.deviceMonotonicTimer
import io.github.nosorae.aiopic.libs.tts.api.Tts
import io.github.nosorae.aiopic.libs.workqueue.api.WorkQueue
import io.github.nosorae.aiopic.packs.opicen.OpicEn
import io.github.nosorae.aiopic.packs.opicen.OpicPrompt
import io.github.nosorae.aiopic.packs.opicen.OpicQuestionType
import io.github.nosorae.aiopic.packs.opicen.PromptFile
import io.github.nosorae.aiopic.packs.opicen.coachProfile
import io.github.nosorae.aiopic.packs.opicen.polishPrompt
import io.github.nosorae.aiopic.packs.opicen.scriptPrompt
import io.github.nosorae.aiopic.ui.featureexam.ExamContent
import io.github.nosorae.aiopic.ui.featureexam.ExamRepository
import io.github.nosorae.aiopic.ui.featureexam.ExamViewModel
import io.github.nosorae.aiopic.ui.featureexam.SurveyStore
import io.github.nosorae.aiopic.ui.featureexam.SurveyViewModel
import io.github.nosorae.aiopic.ui.featurehistory.HistoryScript
import io.github.nosorae.aiopic.ui.featurehistory.HistoryStore
import io.github.nosorae.aiopic.ui.featurehistory.HistoryViewModel
import io.github.nosorae.aiopic.ui.featurehome.ExamSessionSource
import io.github.nosorae.aiopic.ui.featurehome.HomeViewModel
import io.github.nosorae.aiopic.ui.featurelive.CoachFactory
import io.github.nosorae.aiopic.ui.featurelive.CoachScriptViewModel
import io.github.nosorae.aiopic.ui.featurelive.CoachStartViewModel
import io.github.nosorae.aiopic.ui.featurelive.LiveCoachViewModel
import io.github.nosorae.aiopic.ui.featurelive.LiveConsent
import io.github.nosorae.aiopic.ui.featurelive.RehearsalFiles
import io.github.nosorae.aiopic.ui.featurelive.TargetGradeStore
import io.github.nosorae.aiopic.ui.featureonboarding.DomainError
import io.github.nosorae.aiopic.ui.featureonboarding.DomainResult
import io.github.nosorae.aiopic.ui.featureonboarding.consent.ConsentViewModel
import io.github.nosorae.aiopic.ui.featureonboarding.consent.VoiceConsent
import io.github.nosorae.aiopic.ui.featureonboarding.key.KeyChecker
import io.github.nosorae.aiopic.ui.featureonboarding.key.KeyEntryViewModel
import io.github.nosorae.aiopic.ui.featureonboarding.start.DecideStartUseCase
import io.github.nosorae.aiopic.ui.featureonboarding.start.StartViewModel
import io.github.nosorae.aiopic.ui.featurepractice.PickPracticeQuestionUseCase
import io.github.nosorae.aiopic.ui.featurepractice.PracticeRepository
import io.github.nosorae.aiopic.ui.featurepractice.PracticeTabViewModel
import io.github.nosorae.aiopic.ui.featurepractice.PracticeViewModel
import io.github.nosorae.aiopic.ui.featurereport.AnswerReportViewModel
import io.github.nosorae.aiopic.ui.featurereport.ReportStore
import io.github.nosorae.aiopic.ui.featurereport.ScriptVoice
import io.github.nosorae.aiopic.ui.featurereport.SessionReportViewModel
import io.github.nosorae.aiopic.ui.featurereport.flag.FlagStore
import io.github.nosorae.aiopic.ui.featuresettings.SettingsViewModel
import io.github.nosorae.aiopic.ui.featuresettings.consent.PendingAnalysis
import io.github.nosorae.aiopic.ui.featuresettings.export.FlagExportStore
import io.github.nosorae.aiopic.ui.featuresettings.export.FlagExportViewModel
import io.github.nosorae.aiopic.ui.featuresettings.haptics.HapticsStore
import io.github.nosorae.aiopic.ui.featuresettings.model.ModelSelectionStore
import io.github.nosorae.aiopic.ui.featuresettings.practice.AnswerTimerStore
import io.github.nosorae.aiopic.ui.featuresettings.practice.QuestionFileStore
import io.github.nosorae.aiopic.ui.featuresettings.theme.ThemeModeStore
import io.github.nosorae.aiopic.ui.featuresettings.voice.QuestionVoiceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.MissingResourceException
import kotlin.time.Clock
import kotlin.time.DurationUnit

/**
 * 앱 하나의 DI 그래프. 화면은 여기서 ViewModel 이나 assisted 팩토리를 꺼낸다(ADR 0004, 0007).
 * 기기에 묶인 것(키 보관소, DB, 녹음기, 음성, 대기열, 코치 오디오)은 플랫폼 진입점이 만들어 [Factory] 인자로 넘긴다.
 * 그래서 공통 테스트가 메모리 가짜로 같은 그래프를 만든다.
 */
@DependencyGraph(AppScope::class)
interface AppGraph {
    val startViewModel: StartViewModel

    /** 첫 실행 화면(S1, S2)을 마친 뒤 다음 화면을 다시 정한다(F1.0). */
    val decideStartUseCase: DecideStartUseCase

    /** 홈의 모의고사 버튼이 이어 볼 회차를 찾는다(F3). */
    val examSessionSource: ExamSessionSource

    val keyEntryViewModelFactory: KeyEntryViewModel.Factory

    val consentViewModel: ConsentViewModel

    val homeViewModel: HomeViewModel

    val examViewModelFactory: ExamViewModel.Factory

    /** 설정에서 여는 서베이. 모의고사 서베이와 같은 저장값을 바꾼다. */
    val surveyViewModel: SurveyViewModel

    val practiceViewModelFactory: PracticeViewModel.Factory

    val practiceTabViewModel: PracticeTabViewModel

    val sessionReportViewModelFactory: SessionReportViewModel.Factory

    val answerReportViewModelFactory: AnswerReportViewModel.Factory

    val historyViewModel: HistoryViewModel

    val settingsViewModel: SettingsViewModel

    val coachStartViewModel: CoachStartViewModel

    /** 코치 시작 시트가 코치와 이야기할 질문을 고른다. */
    val pickPracticeQuestionUseCase: PickPracticeQuestionUseCase

    val liveCoachViewModelFactory: LiveCoachViewModel.Factory

    val coachScriptViewModelFactory: CoachScriptViewModel.Factory

    val flagExportViewModel: FlagExportViewModel

    /** 앱 틀이 구독해서 화면 모드를 고르는 즉시 테마를 바꾼다(F9.1). */
    val themeModeStore: ThemeModeStore

    /** 앱 틀이 플랫폼 진동에 설정의 진동 켜기/끄기를 씌울 때 쓴다. */
    val settings: AppSettings

    /**
     * 분석 작업 표(#205). 플랫폼 대기열이 깨어날 때마다 [AnalysisJobs.drain] 을 부르고(F5.1, F5.3), 앱 틀이 켤 때 [AnalysisJobs.recover] 를 부른다.
     * 대기열은 그래프보다 먼저 만들어져서 이 값을 나중에 이어 준다.
     */
    val analysisJobs: AnalysisJobs

    /** 디버깅 화면의 DB 패널이 읽는다. */
    val opicStore: OpicStore

    /** 분석 작업 표와 앱 밖 진행, 끝 알림이 같은 시계로 시각을 잰다(#229). */
    val clock: Clock

    @Provides fun clock(): Clock = Clock.System

    @Provides fun voiceConsent(settings: AppSettings): VoiceConsent = settings

    @Provides fun provideThemeModeStore(settings: AppSettings): ThemeModeStore = settings

    @Provides fun provideModelSelectionStore(settings: AppSettings): ModelSelectionStore = settings

    @Provides fun answerTimerStore(settings: AppSettings): AnswerTimerStore = settings

    @Provides fun questionFileStore(settings: AppSettings): QuestionFileStore = settings

    @Provides fun hapticsStore(settings: AppSettings): HapticsStore = settings

    @Provides fun surveyStore(settings: AppSettings): SurveyStore = settings

    @Provides fun targetGradeStore(settings: AppSettings): TargetGradeStore = settings

    @Provides fun liveConsent(settings: AppSettings): LiveConsent = settings

    /**
     * 코치 세션 하나를 돌릴 코치(F11). 시험 팩의 코치 프로필, 사용자 키로 여는 Gemini Live 연결, 플랫폼 오디오, 앱 DB 의 코치 표를 묶는다.
     * 코치 음성은 질문 음성과 같은 목소리다. 질문 글은 시작 시트가 고른 문항에서 경로로 받는다.
     */
    @Provides
    fun coachFactory(database: OpicDatabase, audio: DuplexAudio, keyVault: KeyVault, clock: Clock): CoachFactory = CoachFactory { question, targetGrade, scope ->
        LiveCoach(
            profile = coachProfile(question, targetGrade),
            transport = GeminiLiveTransport(apiKey = { keyVault.get(GeminiModels.PROVIDER) }, voice = QUESTION_VOICE_NAME),
            audio = audio,
            store = CoachStore(database),
            clock = clock,
            scope = scope,
        )
    }

    /**
     * 코치 리허설 녹음(F13.1). 녹음 폴더 아래 `coach/세션 id` 에 쓰고, 조각을 이어 붙여 Gemini 받아쓰기만 받는다.
     * 이어 붙인 파일은 조각 이름 규칙과 겹치지 않아 다음 받아쓰기의 조각 목록에 섞이지 않는다.
     */
    @Provides
    fun rehearsalFiles(@Named(RECORDINGS) recordingsRoot: String, recorder: Recorder, keyVault: KeyVault, client: GeminiClient): RehearsalFiles {
        val transcriber = GeminiTranscriber(client, { keyVault.get(GeminiModels.PROVIDER) }, GeminiModels.transcriptModel)
        return object : RehearsalFiles {
            override fun directory(sessionId: String) = "$recordingsRoot/coach/$sessionId"

            override suspend fun transcribe(directory: String): AnalysisResult<Transcript> {
                val chunks = recorder.chunks(directory).ifEmpty { return AnalysisResult.Unscorable(UnscorableReason.Silent) }
                val output = "$directory/rehearsal.m4a"
                recorder.merge(chunks, output)
                return transcriber.transcribe(Recording(readFile(output), "audio/mp4", emptyList()))
            }

            override suspend fun clear(directory: String) = listFiles(directory).forEach(::deleteFile)
        }
    }

    @Provides fun coachStore(database: OpicDatabase): CoachStore = CoachStore(database)

    /**
     * 코치 세션으로 스크립트를 만들고 다듬는다(F14.1, F14.4). 설정에서 고른 분석 모델로 보내고, 프롬프트는 시험 팩 파일을 채운다.
     */
    @Provides
    fun scriptWriter(client: GeminiClient, keyVault: KeyVault, settings: AppSettings): ScriptWriter = GeminiScriptWriter(
        client = client,
        apiKey = { keyVault.get(GeminiModels.PROVIDER) },
        model = { GeminiModels.choose(settings.selectedModelId()).model },
        scriptPrompt = { session, question -> scriptPrompt(OpicPrompt.Script.load(), question, session) },
        polishPrompt = { edited, expressions, question, grade -> polishPrompt(OpicPrompt.ScriptPolish.load(), question, edited, expressions, grade) },
    )

    /** 목표 등급별 스크립트 길이(F14.2). */
    @Provides fun scriptLengths(): Map<String, ScriptLength> = OpicEn.scriptLengths

    @Provides fun pendingAnalysis(jobs: AnalysisJobs): PendingAnalysis = PendingAnalysis(jobs::cancelAll)

    @Provides
    fun questionVoiceStore(settings: AppSettings, @Named(DEVICE_TTS) device: Tts): QuestionVoiceStore = object : QuestionVoiceStore {
        override suspend fun naturalVoiceOn() = settings.naturalVoiceOn

        override suspend fun setNaturalVoiceOn(on: Boolean) = settings.setNaturalVoiceOn(on)

        override fun hasHighQualityDeviceVoice() = device.hasHighQualityVoice()
    }

    /**
     * 화면이 쓰는 음성. 플랫폼 기기 음성에 미리 만든 Gemini 음성을 씌운다(#161). 모델이 낸 질문의 음성은 녹음 폴더 옆 `voices` 에 받아 둔다.
     * 출제 모델처럼 분석 모델 설정과 상관없이 [QUESTION_VOICE_MODEL] 로 받는다.
     */
    @SingleIn(AppScope::class)
    @Provides
    fun questionVoice(@Named(DEVICE_TTS) device: Tts, settings: AppSettings, @Named(RECORDINGS) recordingsRoot: String, keyVault: KeyVault, client: GeminiClient, pack: ExamPack): QuestionVoice = QuestionVoice(device, { settings.naturalVoiceOn }, pack.pool, pack.voice, "$recordingsRoot/voices", synthesize = { text ->
        keyVault.get(GeminiModels.PROVIDER)?.let { client.speech(it, QUESTION_VOICE_MODEL, QUESTION_VOICE_NAME, text) }
    })

    @Provides fun tts(voice: QuestionVoice): Tts = voice

    /** 답변 리포트의 최종 스크립트 음성(#217). 질문 음성과 같은 모델과 목소리로, 자연스러운 음성 설정이 켜졌을 때만 받는다. */
    // ponytail: 마지막에 받은 스크립트 하나만 메모리에 둔다. 같은 답을 다시 들을 때 다시 받지 않으려는 것이다. 여러 답을 오가며 자주 들으면 파일로 남긴다.
    @SingleIn(AppScope::class)
    @Provides
    fun scriptVoice(settings: AppSettings, keyVault: KeyVault, client: GeminiClient): ScriptVoice {
        var last: Pair<String, ByteArray>? = null
        return ScriptVoice { text ->
            last?.takeIf { it.first == text }?.second ?: if (!settings.naturalVoiceOn) {
                null
            } else {
                keyVault.get(GeminiModels.PROVIDER)?.let { client.speech(it, QUESTION_VOICE_MODEL, QUESTION_VOICE_NAME, text) }?.also { last = text to it }
            }
        }
    }

    // ponytail: 첫 버전은 제공사가 Gemini 하나라 목록도 하나다.
    @Provides fun modelCatalog(): ModelCatalog = GeminiModels

    @Provides
    fun keyChecker(client: GeminiClient): KeyChecker = KeyChecker { _, key ->
        // ponytail: 키 확인 전용 API 가 없어 기본 모델에 짧은 요청을 보낸다. 무료 키의 하루 한도에서 한 번을 쓴다.
        val result = client.generate(LlmRequest(apiKey = key, model = GeminiModels.defaultModel.id, prompt = "Reply with OK."))
        DebugTools.log(if (result is LlmResult.Success) DebugLevel.Info else DebugLevel.Warn, "KeyCheck", result.toString())
        geminiKeyCheck(result)
    }

    @SingleIn(AppScope::class)
    @Provides
    fun opicStore(database: OpicDatabase): OpicStore = OpicStore(database)

    @SingleIn(AppScope::class)
    @Provides
    fun storeReads(store: OpicStore, jobs: AnalysisJobs, coach: CoachStore, settings: AppSettings): StoreReads = StoreReads(store, jobs, scriptRows(coach, settings))

    @Provides fun examSessionSource(reads: StoreReads): ExamSessionSource = reads

    @Provides fun reportStore(reads: StoreReads): ReportStore = reads

    @Provides fun historyStore(reads: StoreReads): HistoryStore = reads

    @Provides fun flagStore(reads: StoreReads): FlagStore = reads

    @Provides fun flagExportStore(reads: StoreReads): FlagExportStore = reads

    @Provides fun examSpec(): ExamSpec = OpicEn.examSpec

    @Provides fun gradeScale(): GradeScale = OpicEn.gradeScale

    /** 기록과 리포트에 보일 문항 유형 이름. 시험 팩의 유형 id 를 한국어로 옮긴다. */
    @Provides
    fun questionTypeNames(): Map<QuestionType, String> = mapOf(
        OpicQuestionType.SelfIntro to "자기소개",
        OpicQuestionType.Description to "묘사",
        OpicQuestionType.Routine to "습관",
        OpicQuestionType.PastExperience to "과거 경험",
        OpicQuestionType.RoleplayAsk to "롤플레이 질문",
        OpicQuestionType.RoleplaySolve to "롤플레이 해결",
        OpicQuestionType.RoleplayExperience to "롤플레이 경험",
        OpicQuestionType.Comparison to "비교",
        OpicQuestionType.SocialIssue to "사회 이슈",
    ).mapKeys { it.key.type }

    /** 시험 화면을 열 때마다 만들어서 설정의 답변 타이머(F2.7)를 그때 값으로 읽는다. */
    @Provides
    fun examContent(spec: ExamSpec, settings: AppSettings): ExamContent = ExamContent(spec, OpicEn.survey, answerTimer = settings.answerTimer)

    /** 미리 보낸 출제 요청을 시험 화면을 다시 열어도 이어 받도록 앱에 하나만 둔다. 요청은 화면보다 오래 살아서 앱 범위에서 돈다. */
    @SingleIn(AppScope::class)
    @Provides
    fun examRepository(
        store: OpicStore,
        jobs: AnalysisJobs,
        spec: ExamSpec,
        @Named(RECORDINGS) recordingsRoot: String,
        settings: AppSettings,
        keyVault: KeyVault,
        client: GeminiClient,
        pack: ExamPack,
        voice: QuestionVoice,
    ): ExamRepository {
        val writer = GeminiQuestionWriter(client) { keyVault.get(GeminiModels.PROVIDER) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        return StoreExamRepository(store, jobs, spec, pack.pool, recordingsRoot, settings, OpicEn.MAX_OUTSIDE_TOPICS, scope, OpicEn.survey::nearTopics, voice::prepare, voice::clean) { session, part, asked ->
            examQuestions(session, part, asked, store, settings, writer, pack.questionsPrompt)
        }
    }

    @Provides
    fun practiceRepository(store: OpicStore, jobs: AnalysisJobs, settings: AppSettings, @Named(RECORDINGS) recordingsRoot: String): PracticeRepository = StorePracticeRepository(store, jobs, { practicePool(settings) }, recordingsRoot)

    @SingleIn(AppScope::class)
    @Provides
    fun analysisJobs(store: OpicStore, queue: WorkQueue, client: GeminiClient, keyVault: KeyVault, settings: AppSettings, recorder: Recorder, clock: Clock): AnalysisJobs = analysisJobs(
        store = store,
        queue = queue,
        client = client,
        keyVault = keyVault,
        settings = settings,
        answerRequest = { answer, model -> analysisRequest(answer, model, recorder) },
        gradeRequest = { answers, model -> gradeRequest(answers, model, recorder) },
        clock = clock,
    )

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides settings: AppSettings,
            @Provides keyVault: KeyVault,
            @Provides database: OpicDatabase,
            @Provides @Named(RECORDINGS) recordingsRoot: String,
            @Provides recorder: Recorder,
            @Provides @Named(DEVICE_TTS) tts: Tts,
            @Provides workQueue: WorkQueue,
            @Provides duplexAudio: DuplexAudio,
            @Provides gemini: GeminiClient,
            @Provides timer: MonotonicTimer,
            @Provides pack: ExamPack,
        ): AppGraph
    }
}

private const val RECORDINGS = "recordingsRoot"
private const val DEVICE_TTS = "deviceTts"

/** 질문 음성 모델과 목소리(#161, ADR 0031). 내장 문항 음성을 만드는 `packs/opic-en/scripts/make_question_voices.py` 와 같아야 한 회차 안에서 목소리가 바뀌지 않는다. */
internal const val QUESTION_VOICE_MODEL = "gemini-3.8-flash-tts"
internal const val QUESTION_VOICE_NAME = "Kore"

/**
 * 모의고사 출제와 질문 음성에 쓰는 시험 팩 파일. 호스트 JVM 테스트는 Compose 리소스를 못 읽어서 바꿔 넣는다.
 */
class ExamPack(
    val pool: suspend () -> List<Question> = OpicEn::questionPool,
    val questionsPrompt: suspend () -> PromptFile = { OpicPrompt.Questions.load() },
    /** 내장 문항 id 로 미리 만든 질문 음성을 읽는다(#161). */
    val voice: suspend (String) -> ByteArray? = OpicEn::questionVoice,
)

/**
 * 시험 팩의 출제 프롬프트에 서베이 주제, 수준, 재조정, 약점 유형(낮은 점수부터), 이미 낸 질문, 받을 문항의 번호와 유형을 채워
 * Gemini 에 회차의 한 몫을 받는다(F6.1, ADR 0030). 음성 전송 동의가 없으면 보내지 않는다. 번호 0 은 점검의 연습 문항이다.
 * 설정에서 고른 분석 모델이 아니라 출제 모델로 보낸다.
 */
private suspend fun examQuestions(
    session: ExamSession,
    part: List<IndexedValue<QuestionType>>,
    asked: List<Question>,
    store: OpicStore,
    settings: AppSettings,
    writer: GeminiQuestionWriter,
    prompt: suspend () -> PromptFile,
): List<Question>? {
    if (!settings.isGiven()) return null
    val file = prompt()
    val weak = weaknessScores(store.answers().first(), store.flags().first())
    val text = file.fill(
        mapOf(
            "topics" to session.surveyTopics.joinToString(),
            "level" to session.level.toString(),
            "recalibration" to (session.recalibration?.name?.lowercase() ?: "not chosen yet"),
            "weakTypes" to weak.joinToString { "${it.type.id} (${it.score})" }.ifEmpty { "none" },
            "asked" to asked.withIndex().joinToString("; ") { (number, it) -> "$number. ${it.type.id}, ${it.topic}" }.ifEmpty { "none" },
            "order" to part.joinToString("\n") { (number, type) -> "$number. ${type.id}" },
        ),
    )
    return writer.write(GeminiModels.questionModel, Prompt(file.version, text, OpicPrompt.Questions.responseSchema.toString()), part.map { it.value }, idPrefix = "${session.id}-")
}

/**
 * 한 문항 연습의 풀. 시험 팩의 내장 문항에 사용자가 설정에서 불러온 문항(F6.3)을 더하고, 저장한 서베이 주제의 질문을 먼저 낸다([preferTopics]). 모의고사 출제에는 넣지 않는다.
 * 시험 팩의 문항 파일을 못 읽으면 불러온 문항만 쓰고, 둘 다 없으면 연습 화면이 죽지 않고 "낼 질문이 없습니다" 오류 화면을 보인다.
 */
internal suspend fun practicePool(settings: AppSettings, builtIn: suspend () -> List<Question> = OpicEn::questionPool): List<Question> {
    val pack = try {
        builtIn()
    } catch (_: MissingResourceException) {
        emptyList()
    }
    // 저장할 때 검사를 마친 파일이다.
    return preferTopics(pack + settings.questionFile()?.let(::parseQuestions).orEmpty(), settings.surveyAnswers()?.topics.orEmpty())
}

/**
 * 한 문항 연습이 서베이에서 고른 주제의 질문을 먼저 내게 한다. 유형마다 고른 주제의 질문이 있으면 그것만 남기고,
 * 없으면(자기소개, 롤플레이처럼 서베이와 상관없는 유형) 그 유형 질문을 모두 둔다. 그래서 약점 유형 고르기(F4.1)는 그대로다.
 * 서베이를 한 번도 내지 않았으면 풀 그대로다.
 */
internal fun preferTopics(pool: List<Question>, topics: List<String>): List<Question> = pool.groupBy { it.type }.values.flatMap { sameType -> sameType.filter { it.topic in topics }.ifEmpty { sameType } }

/**
 * 플랫폼 진입점이 Metro 컴파일러 플러그인 없이 그래프를 만들 수 있게 여기서 만든다. 인자는 [AppGraph.Factory] 와 같다.
 * [gemini], [timer], [pack] 은 앱이 기본값을 쓰고, 테스트가 저장해 둔 Gemini 응답, 손으로 넘기는 시계, 메모리 문항 풀과 프롬프트로 바꾼다.
 */
fun createAppGraph(
    settings: AppSettings,
    keyVault: KeyVault,
    database: OpicDatabase,
    recordingsRoot: String,
    recorder: Recorder,
    tts: Tts,
    workQueue: WorkQueue,
    duplexAudio: DuplexAudio,
    gemini: GeminiClient = GeminiClient(),
    timer: MonotonicTimer = deviceMonotonicTimer(),
    pack: ExamPack = ExamPack(),
): AppGraph = createGraphFactory<AppGraph.Factory>().create(settings, keyVault, database, recordingsRoot, recorder, tts, workQueue, duplexAudio, gemini, timer, pack)

/** 키 확인 요청 결과를 키 입력 화면의 결과로 옮긴다(F1.1). 모델이 없다는 답도 키는 통과했다는 뜻이다. */
internal fun geminiKeyCheck(result: LlmResult): DomainResult<Unit> = when (result) {
    is LlmResult.Success, LlmResult.ModelNotFound -> DomainResult.Success(Unit)
    LlmResult.InvalidKey -> DomainResult.Failure(DomainError.InvalidKey)
    is LlmResult.Retryable, is LlmResult.Rejected -> DomainResult.Failure(DomainError.Unavailable)
}

/**
 * 분석 작업 표(#205). 답변 분석과 회차 등급을 설정에서 고른 모델로 보내고, 작업을 넣으면 플랫폼 대기열을 깨운다.
 * 테스트가 가짜 Gemini 클라이언트를 넣을 수 있게 그래프 밖에 둔다. 요청을 따로 받는 것은 호스트 JVM 테스트에서 시험 팩의 프롬프트 파일(Compose 리소스)을 못 읽어서다.
 */
// ponytail: 등급 요청은 고른 모델이 내려가도 기본 모델로 돌리지 않고 실패로 끝낸다. 다음 답변 분석이 기본 모델로 바꿔 두므로 다시 분석하기로 다시 보낸다.
internal fun analysisJobs(
    store: OpicStore,
    queue: WorkQueue,
    client: GeminiClient,
    keyVault: KeyVault,
    settings: AppSettings,
    answerRequest: suspend (Answer, ModelInfo) -> AnalysisRequest,
    gradeRequest: suspend (List<Answer>, ModelInfo) -> GradeRequest,
    clock: Clock = Clock.System,
): AnalysisJobs {
    val gemini = GeminiAnalyzer(client) { keyVault.get(GeminiModels.PROVIDER) }
    return AnalysisJobs(
        store = store,
        router = AnswerRouter(FallBackToDefaultModel(gemini, settings), NoTwoStepPath, NoTwoStepPath),
        grader = gemini,
        consented = settings::isGiven,
        online = { queue.online },
        answerRequest = { answer -> answerRequest(answer, GeminiModels.choose(settings.selectedModelId()).model) },
        gradeRequest = { answers -> gradeRequest(answers, GeminiModels.choose(settings.selectedModelId()).model) },
        wake = queue::wake,
        clock = clock,
    )
}

/**
 * 고른 모델이 내려가 분석이 [AnalysisError.ModelUnavailable] 로 끝나면 기본 모델을 저장하고 다시 보내게 한다(F5.8).
 * 다시 보낼 때 [analysisJobs] 가 저장된 모델을 읽어서 기본 모델로 간다. 설정 화면은 [AppSettings.takeFellBackFrom] 으로 알린다.
 * 기본 모델마저 없으면 돌릴 곳이 없어 그대로 실패로 둔다.
 */
private class FallBackToDefaultModel(private val analyzer: AudioAnswerAnalyzer, private val settings: AppSettings) : AudioAnswerAnalyzer {
    override suspend fun analyze(model: ModelInfo, prompt: Prompt, question: Question, recording: Recording): AnalysisResult<Feedback> {
        val result = analyzer.analyze(model, prompt, question, recording)
        val default = GeminiModels.choose(model.id, unavailable = true).model
        if (result != AnalysisResult.Failed(AnalysisError.ModelUnavailable) || model == default) return result
        settings.fallBack(from = model.id, to = default.id)
        return AnalysisResult.Failed(AnalysisError.Retryable)
    }
}

/**
 * 답변 하나의 분석 요청을 만든다. 조각을 같은 폴더의 `answer.m4a` 하나로 이어 붙이고(조각 이름 규칙과 겹치지 않는다)
 * 시험 팩의 답변 프롬프트에 문항과 이어 녹음 위치를 채운다(F3.4, F5.7).
 */
private suspend fun analysisRequest(answer: Answer, model: ModelInfo, recorder: Recorder): AnalysisRequest {
    val recording = recording(answer, recorder)
    val prompt = OpicPrompt.Answer.load()
    val text = prompt.fill(
        mapOf(
            "question" to answer.question.text,
            "questionType" to answer.question.type.id,
            "resumedAt" to recording.resumedAt.joinToString { it.toDouble(DurationUnit.SECONDS).toString() }.ifEmpty { "none" },
        ),
    )
    return AnalysisRequest(model, Prompt(prompt.version, text, OpicPrompt.Answer.responseSchema.toString()), recording)
}

/**
 * 회차 등급 요청을 만든다(F5.3). 시험 팩의 등급 프롬프트에 답한 순서의 답변 분석 결과(평가할 수 없음 포함)를 JSON 으로 채우고
 * 같은 순서의 녹음을 붙인다. 녹음은 답변 분석 때와 같은 방법으로 이어 붙인다.
 */
private suspend fun gradeRequest(answers: List<Answer>, model: ModelInfo, recorder: Recorder): GradeRequest {
    val prompt = OpicPrompt.SessionGrade.load()
    val text = prompt.fill(mapOf("answers" to Json.encodeToString(answers.map { it.analysis })))
    return GradeRequest(model, Prompt(prompt.version, text, OpicPrompt.SessionGrade.responseSchema.toString()), OpicEn.gradeScale, answers.map { recording(it, recorder) })
}

/** 답변의 조각을 같은 폴더의 `answer.m4a` 하나로 이어 붙여 읽는다. */
private suspend fun recording(answer: Answer, recorder: Recorder): Recording {
    // 조각이 없는 답변(마이크를 못 연 경우)은 빈 녹음으로 보낸다. 모델이 평가할 수 없음으로 답한다.
    val output = answer.chunkPaths.firstOrNull()?.substringBeforeLast('/')?.plus("/answer.m4a")
    val resumedAt = output?.let { recorder.merge(answer.chunkPaths, it) }.orEmpty()
    return Recording(output?.let(::readFile) ?: ByteArray(0), "audio/mp4", resumedAt)
}

/** 녹음 파일 하나를 통째로 읽는다. 분석 요청 한 번에 답변 하나(길어야 몇 MB)만 싣는다. */
internal expect fun readFile(path: String): ByteArray

/** 없으면 null 이다. 미리 받아 둔 질문 음성을 찾을 때 쓴다. */
internal expect fun readFileOrNull(path: String): ByteArray?

/** 폴더가 없으면 만들고 통째로 쓴다. */
internal expect fun writeFile(path: String, bytes: ByteArray)

/** 폴더 바로 아래 파일의 경로. 폴더가 없으면 빈 목록이다. */
internal expect fun listFiles(directory: String): List<String>

/** 없으면 아무것도 안 한다. */
internal expect fun deleteFile(path: String)

// ponytail: Gemini 목록은 모두 음성을 직접 들어서 두 단계 경로(받아쓰기, 글 분석)를 쓰지 않는다. 음성을 못 듣는 모델을 더할 때 실제 구현을 넣는다.
private object NoTwoStepPath : Transcriber, TranscriptAnalyzer {
    override suspend fun transcribe(recording: Recording): AnalysisResult<Transcript> = AnalysisResult.Failed(AnalysisError.Rejected)

    override suspend fun analyze(model: ModelInfo, prompt: Prompt, question: Question, transcript: Transcript): AnalysisResult<TextAnalysis> = AnalysisResult.Failed(AnalysisError.Rejected)
}

/** 기록 탭의 스크립트 줄(F14.6). 세션에는 질문 id 만 있어 연습 풀에서 질문 글을 찾는다. 풀에서 빠진 질문은 글이 비어 목표 등급만 보인다. */
private fun scriptRows(coach: CoachStore, settings: AppSettings): Flow<List<HistoryScript>> = coach.scriptSessions().map { sessions ->
    val texts = if (sessions.isEmpty()) emptyMap() else practicePool(settings).associate { it.id to it.text }
    sessions.map { HistoryScript(it.id, it.questionId, texts[it.questionId].orEmpty(), it.targetGrade) }
}
