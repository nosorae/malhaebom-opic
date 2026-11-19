package io.github.nosorae.aiopic.engine.speaking

import io.github.nosorae.aiopic.engine.review.CardKind
import io.github.nosorae.aiopic.engine.review.CardStore
import io.github.nosorae.aiopic.engine.review.ExpressionCard
import io.github.nosorae.aiopic.engine.review.Rating
import io.github.nosorae.aiopic.engine.review.ReviewAmount
import io.github.nosorae.aiopic.engine.review.nextDayStart
import io.github.nosorae.aiopic.engine.review.todayQueue
import io.github.nosorae.aiopic.libs.duplexaudio.api.DuplexAudio
import io.github.nosorae.aiopic.libs.livegemini.LiveEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * 코치와의 Live 연결 하나(F16). 앱이 `libs/live-gemini` 의 LiveSession 에 키와 연결 설정(말 끝 판정, 끼어들기)을 넣어 감싼다.
 * 모으는 동안 연결을 유지하고, [resumeHandle] 이 있으면 앞 대화를 이어 붙인다. 연결이 끝나면 흐름이 끝난다.
 * [route] 가 스피커면 주변 소리가 섞이므로 말 시작과 끝 감도를 낮게 둔다(F20.5).
 * [interruptible] 이 false 면 코치가 말하는 중에 사용자가 말해도 서버가 끊지 않는다(F16.17). 세션이 기기 쪽 끊기와 같은 값을 넘긴다.
 */
interface SpeakingLink {
    fun connect(instruction: String, tools: List<JsonObject>, resumeHandle: String?, route: AudioRoute, interruptible: Boolean): Flow<LiveEvent>

    /** 16kHz 모노 16비트 PCM. */
    suspend fun sendAudio(pcm: ByteArray)

    /** 말이 끝나 침묵이 시작됐다. 서버 음성 감지가 말 끝을 알게 한다. */
    suspend fun sendAudioEnd()

    /** 코치에게 글로 알린다([Cue]). */
    suspend fun sendText(text: String)

    suspend fun sendToolResponse(id: String, name: String, result: JsonElement)
}

/**
 * 시험 팩이 넘기는 코치 설정. [instruction] 은 [KEYS] 빈칸을 가진 지시 글이고, [cues] 는 `{{question}}` 빈칸을 가질 수 있는 알림 글이다.
 * 도구 이름은 [SpeakingTools] 를 쓴다. 엔진이 채우지 않는 빈칸이 지시에 있으면 만들지 않는다. `{{이름}}` 이 그대로 모델에 가지 않게 한다.
 */
class SpeakingProfile(val instruction: String, val tools: List<JsonObject>, val cues: Map<Cue, String>) {
    init {
        val unknown = PLACEHOLDER.findAll(instruction).map { it.groupValues[1] }.toSet() - KEYS
        require(unknown.isEmpty()) { "엔진이 채우지 않는 지시 빈칸 $unknown" }
    }

    companion object {
        /** 지시 빈칸 이름. [SpeakingSession] 이 이 이름마다 값을 채운다. */
        val KEYS = setOf("topic", "repeat_question", "minutes", "kinds", "review_cards", "frequent_errors", "grammar_nudge")
        internal val PLACEHOLDER = Regex("""\{\{(\w+)\}\}""")
    }
}

/**
 * 시작 시트와 설정에서 고른 값(F16.2, F16.8, F16.22, F18.7). [topics] 는 서베이 주제와 업무 주제다.
 * [bargeIn] 은 설정의 말로 끊기다(F16.17). 세션이 이 값 하나로 서버의 끊기(연결 설정)와 스피커 모드의 기기 끊기(F20.4)를 함께 정한다.
 */
data class SpeakingSetup(
    val planned: Duration,
    val route: AudioRoute,
    val topics: List<String>,
    val oralReview: Boolean = true,
    val grammarNudge: Boolean = false,
    val amount: ReviewAmount = ReviewAmount.Normal,
    val bargeIn: Boolean = true,
)

/** 말로 복습에서 푸는 카드 수(F16.8). */
private const val ORAL_REVIEW_CARDS = 3

/** 이어 붙이기 핸들은 끊긴 뒤 2시간 유효하다. 받은 시각부터 재서 조금 일찍 만료로 본다. */
private val RESUME_LIMIT = 2.hours

/** 코치 음성이 끝나고 이만큼 사용자 말소리가 없으면 대답 없음이다(F16.12). */
// ponytail: 실기기에서 생각하는 시간보다 짧아 재촉하면 이 값만 늘린다.
internal val NO_ANSWER_WAIT = 8.seconds

/** 다시 연결은 이만큼 기다린 뒤 하고 실패할 때마다 두 배로 늘린다(1초, 2초, 4초). 잠깐 끊긴 네트워크가 돌아올 틈을 준다(F16.15). */
private val RECONNECT_DELAY = 1.seconds

/** 말이 멈춘 뒤 이만큼 침묵 조각을 보내고 오디오 끝을 보낸다. 라이브 코치와 같은 값이다. */
private val SPEECH_END_GRACE = 1.seconds

/**
 * 말하기 연습 하나를 돌린다(design.md 12.1). 일어난 일을 [reduce] 로 상태에 반영해 응답마다 저장하고, [cues] 를 코치에게 글로 보내고,
 * 연결 하나를 끝까지 이어 쓴다. 연결이 끊기면 핸들로 다시 연결한다(F16.15). 앱 범위 객체가 들고 서비스가 붙잡는다(ADR 0038).
 */
class SpeakingSession(
    private val profile: SpeakingProfile,
    private val link: SpeakingLink,
    private val audio: DuplexAudio,
    private val store: SpeakingStore,
    private val cards: CardStore,
    private val clock: Clock,
    private val timeZone: TimeZone,
    private val scope: CoroutineScope,
    /**
     * 연습 기록으로 옮긴 뒤 연습 id 로 부른다. 앱은 `analysisJobs::enqueueCards` 를 넘겨 연습 정리 작업을 넣는다(F16.11, F17.4).
     * 빠뜨리면 정리가 다음 앱 실행의 recover 까지 밀리므로 기본값을 두지 않는다.
     */
    private val onFinished: suspend (String) -> Unit,
) {
    private val _state = MutableStateFlow<SpeakingState?>(null)

    /** 시작하거나 열기 전에는 null 이다. 끝나면 [SpeakingState.finished] 가 참인 마지막 상태가 남는다. */
    val state: StateFlow<SpeakingState?> = _state.asStateFlow()

    private val lock = Mutex()
    private var running: Job? = null
    private var connection: Job? = null
    private lateinit var plan: Plan

    /** 마지막 말소리 조각을 받은 시각과 지금 말하는 중인지. 대답 없음을 판단한다. */
    private var lastSpeechAt: Instant? = null
    private var speaking = false

    /**
     * 지금 연결의 코치 음성 한 턴. 재생은 받은 즉시 시작한다고 보고 받은 길이를 [queued] 에 더한다. [waiting] 은 턴 끝을 받고 재생 끝을 기다리는 일이다.
     * 기기가 코치를 끊었으면(F20.4) 서버가 그 턴을 끝낼 때까지 [dropping] 이라 남은 음성을 버린다.
     */
    private var turnStart: Instant? = null
    private var queued = Duration.ZERO
    private var waiting: Job? = null
    private var dropping = false
    private val bargeIn = BargeIn()

    /** 지시를 만드는 재료. 연결을 다시 열 때도 같은 지시를 쓴다. */
    private class Plan(val setup: SpeakingSetup, val repeat: String?, val review: List<ExpressionCard>, val errors: String, val weak: QuestionKind?)

    /** 주제를 고르고(F16.5) 말로 복습할 카드를 골라(F16.8) 연결한다. */
    suspend fun start(id: String, setup: SpeakingSetup) = lock.withLock {
        val now = clock.now()
        val all = cards.cards().first()
        val review = if (setup.oralReview) todayQueue(all, now, timeZone).filter { !it.firstSeen }.take(ORAL_REVIEW_CARDS) else emptyList()
        val pick = pickTopic(setup.topics, store.topics(), now)
        plan = plan(setup, pick.repeatQuestion, review, all)
        set(SpeakingState(id, now, setup.planned, setup.route, pick.topic, review.map { it.id }), before = null)
    }

    /** 앱 프로세스가 끝난 뒤 저장된 진행 중인 연습을 되살려 핸들로 이어 간다(F16.16). 없으면 false. */
    suspend fun open(setup: SpeakingSetup): Boolean = lock.withLock {
        val saved = store.active() ?: return@withLock false
        val all = cards.cards().first()
        plan = plan(setup, null, all.filter { it.id in saved.reviewCardIds }, all)
        set(saved, before = null)
        true
    }

    /** 화면, 알림, 이어폰 버튼, 오디오 끼어듦이 넣는 일이다. 일시정지, 다시 시작(다시 연결 포함), 끝내기(F16.10, F16.11). */
    suspend fun send(event: SpeakingEvent) = dispatch(event)

    private suspend fun plan(setup: SpeakingSetup, repeat: String?, review: List<ExpressionCard>, all: List<ExpressionCard>) = Plan(setup, repeat, review, frequentErrors(all).joinToString { it.name.snake() }, weakKind(store.pastQuestions()))

    private suspend fun dispatch(event: SpeakingEvent) = lock.withLock { apply(event) }

    private suspend fun apply(event: SpeakingEvent) {
        val before = _state.value ?: return
        val after = reduce(before, event)
        if (after != before) set(after, before)
    }

    private suspend fun set(state: SpeakingState, before: SpeakingState?) {
        if (state.finished) {
            store.finish(state)
            onFinished(state.id)
        } else {
            store.saveActive(state)
        }
        _state.value = state
        before?.let { cues(it, state).forEach { cue -> link.sendText(profile.cues[cue].orEmpty().replace("{{question}}", state.firstQuestion?.text.orEmpty())) } }
        sync(state)
    }

    /** 끝나면 모두 닫는다. 끝나지 않았으면 시계와 마이크를 돌리고, 끊김으로 멈추지 않았으면 연결 하나를 둔다. */
    private fun sync(state: SpeakingState) {
        if (state.finished) {
            running?.cancel()
            connection?.cancel()
            return
        }
        if (running == null) {
            running = scope.launch {
                launch { tick() }
                launch { forwardMic() }
            }
        }
        if (!state.disconnected && connection?.isActive != true) connection = scope.launch { connect() }
    }

    /** 연결이 끝나면 끊김으로 넣고, 끊김으로 멈추지 않았으면 기다렸다가 핸들로 다시 연다. */
    private suspend fun connect() {
        while (true) {
            val state = _state.value ?: return
            if (state.finished || state.disconnected) return
            val handle = state.resumeHandle?.takeIf { state.handleAt != null && clock.now() - state.handleAt < RESUME_LIMIT }
            try {
                talk(state, handle)
            } catch (e: Exception) {
                // 예외가 앱 범위까지 가면 앱이 꺼진다. 끊김으로 세어 다시 연결한다.
                currentCoroutineContext().ensureActive()
            }
            dispatch(SpeakingEvent.Dropped(clock.now()))
            val failed = _state.value?.reconnects ?: return
            if (failed in 1..MAX_RECONNECTS) delay(RECONNECT_DELAY * (1 shl (failed - 1)))
        }
    }

    private suspend fun endTurn(played: Duration) {
        turnStart = null
        queued = Duration.ZERO
        dispatch(SpeakingEvent.CoachTalked(played))
    }

    /** 재생과 대기열을 버리고 지금까지 재생한 만큼만 코치가 말한 시간으로 넣는다(F16.24). */
    private suspend fun stopTurn() {
        audio.flush()
        waiting?.cancel()
        turnStart?.let { endTurn(minOf(queued, clock.now() - it)) }
    }

    private suspend fun talk(state: SpeakingState, handle: String?) = coroutineScope {
        var replied = false
        turnStart = null
        queued = Duration.ZERO
        waiting = null
        dropping = false

        link.connect(instruction(state), profile.tools, handle, state.route, plan.setup.bargeIn).collect { event ->
            // 연결을 받고 곧바로 닫는 서버(할당량 초과 등)를 성공으로 세지 않게, 응답이 처음 온 때 다시 연결 횟수를 0 으로 돌린다(F16.15).
            if (!replied && event.isReply()) {
                replied = true
                dispatch(SpeakingEvent.Connected)
            }
            when (event) {
                LiveEvent.Connected, LiveEvent.Resumed -> Unit

                is LiveEvent.ResumeHandle -> dispatch(SpeakingEvent.HandleReceived(event.handle, clock.now()))

                is LiveEvent.AudioChunk -> if (!dropping) {
                    waiting?.cancel()
                    if (turnStart == null) {
                        turnStart = clock.now()
                        dispatch(SpeakingEvent.CoachStarted)
                    }
                    queued += (event.pcm.size / 2).seconds / DuplexAudio.PLAYBACK_SAMPLE_RATE
                    audio.play(event.pcm)
                }

                // 말로 끊겨 멈춘 코치 음성은 실제로 재생한 만큼만 센다(F16.24).
                LiveEvent.Interrupted -> {
                    dropping = false
                    stopTurn()
                }

                // 재생이 끝날 때까지 기다려 코치 말 끝으로 넣고, 그 뒤 말소리가 없으면 대답 없음이다(F16.12).
                LiveEvent.TurnComplete -> if (dropping) {
                    dropping = false
                } else {
                    turnStart?.let { start ->
                        val since = clock.now()
                        waiting = launch {
                            delay(queued - (clock.now() - start))
                            endTurn(queued)
                            delay(NO_ANSWER_WAIT)
                            val spoke = lastSpeechAt?.let { it >= since } == true
                            if (!speaking && !spoke) dispatch(SpeakingEvent.NoAnswer(clock.now()))
                        }
                    }
                }

                is LiveEvent.InputTranscript -> dispatch(SpeakingEvent.Heard(Speaker.User, event.text))

                is LiveEvent.OutputTranscript -> dispatch(SpeakingEvent.Heard(Speaker.Coach, event.text))

                is LiveEvent.ToolCall -> onTool(event)

                is LiveEvent.Usage -> dispatch(SpeakingEvent.Used(Usage(event.promptByModality, event.responseByModality)))

                // 흐름이 여기서 끝나고 connect() 가 끊김으로 넣는다.
                is LiveEvent.Disconnected, is LiveEvent.Failed -> Unit
            }
        }
    }

    private fun LiveEvent.isReply() = this is LiveEvent.AudioChunk || this == LiveEvent.TurnComplete || this is LiveEvent.ToolCall || this is LiveEvent.InputTranscript || this is LiveEvent.OutputTranscript

    /** [SpeakingProfile.KEYS] 마다 값을 채운다. 이름이 어긋나면 지시를 만들지 않는다. */
    private fun instruction(state: SpeakingState): String {
        val values = mapOf(
            "topic" to state.topic,
            "repeat_question" to (plan.repeat ?: NONE),
            "minutes" to state.planned.inWholeMinutes.toString(),
            "kinds" to questionKinds(state.planned, plan.weak).joinToString { it.toolName },
            "review_cards" to plan.review.joinToString("\n") { "card ${it.id}: ${it.context} | ${it.meaning} | ${it.better}" }.ifEmpty { NONE },
            "frequent_errors" to plan.errors.ifEmpty { NONE },
            "grammar_nudge" to if (plan.setup.grammarNudge) "on" else "off",
        )
        return SpeakingProfile.PLACEHOLDER.replace(profile.instruction) { values.getValue(it.groupValues[1]) }
    }

    /** 카드와 주제를 고치는 도구는 여기서 처리하고, 나머지는 상태 계산에 넘긴다. 버린 호출에도 답해야 모델이 기다리지 않는다. */
    private suspend fun onTool(call: LiveEvent.ToolCall) {
        val error = toolError(call.name, call.args)
        if (error != null) return respond(call, error)
        when (call.name) {
            SpeakingTools.JUDGE_CARD -> judge(call)

            SpeakingTools.SAVE_EXPRESSION -> {
                val now = clock.now()
                val id = _state.value?.id ?: return
                cards.save(
                    ExpressionCard(
                        kind = CardKind.UnknownExpression,
                        said = call.args.text("said")!!,
                        better = call.args.text("better")!!,
                        meaning = call.args.text("meaning")!!,
                        errorType = null,
                        context = call.args.text("context")!!,
                        createdAt = now,
                        dueAt = now.nextDayStart(timeZone),
                        practiceId = id,
                    ),
                )
                respond(call)
                dispatch(SpeakingEvent.ExpressionSaved)
            }

            SpeakingTools.SWITCH_TOPIC -> {
                val topic = _state.value?.topic ?: return
                val pick = pickTopic(plan.setup.topics, store.topics(), clock.now(), exclude = setOf(topic))
                plan = Plan(plan.setup, pick.repeatQuestion, plan.review, plan.errors, plan.weak)
                respond(
                    call,
                    result = buildJsonObject {
                        put("topic", pick.topic)
                        pick.repeatQuestion?.let { put("repeat_question", it) }
                    },
                )
                dispatch(SpeakingEvent.TopicChanged(pick.topic))
            }

            else -> {
                val limit = if (call.name == SpeakingTools.ASK_QUESTION) _state.value?.let { askError(it, call.args) } else null
                respond(call, limit)
                if (limit == null) dispatch(SpeakingEvent.ToolCalled(call.name, call.args, clock.now()))
            }
        }
    }

    /** 판정 전 복습 값을 남기고 화면 복습과 같이 FSRS, 복습 횟수, 복습한 날에 센다(F16.8). 모르겠다는 틀림과 같이 다시다. */
    private suspend fun judge(call: LiveEvent.ToolCall) {
        val id = (call.args["card_id"] as JsonPrimitive).long
        val card = cards.card(id) ?: return respond(call, "card $id not found")
        val now = clock.now()
        val correct = call.args.text("result") == "correct"
        val event = SpeakingEvent.Judged(
            OralReview(
                cardId = id,
                better = card.better,
                correct = correct,
                judgedAtMs = now.toEpochMilliseconds(),
                before = ReviewValues(card.difficulty, card.stability, card.dueAt.toEpochMilliseconds(), card.lastReviewedAt?.toEpochMilliseconds()),
            ),
        )
        val accepted = lock.withLock {
            val before = _state.value ?: return@withLock false
            if (reduce(before, event) == before) return@withLock false
            cards.rate(id, if (correct) Rating.Good else Rating.Again, now, plan.setup.amount)
            apply(event)
            true
        }
        respond(call, if (accepted) null else "card $id is not in this review")
    }

    private suspend fun respond(call: LiveEvent.ToolCall, error: String? = null, result: JsonObject? = null) = link.sendToolResponse(call.id, call.name, result ?: buildJsonObject { if (error == null) put("ok", true) else put("error", error) })

    /** 1초마다 대화 시계를 넣는다. 일시정지 중에도 넣어 일시정지 10분을 본다(F16.12). */
    private suspend fun tick() {
        while (true) {
            val from = clock.now()
            delay(1.seconds)
            val now = clock.now()
            dispatch(SpeakingEvent.TimePassed(now - from, now))
        }
    }

    /**
     * 말소리 조각과 말이 멈춘 뒤 [SPEECH_END_GRACE] 동안의 침묵 조각을 보내고 오디오 끝을 한 번 보낸다.
     * 말소리 조각 길이를 모아 말이 끝날 때 내가 말한 시간으로 넣는다(F16.24). 마이크는 늘 모아야 코치 음성이 재생된다.
     * 일시정지 중에도 보내 코치가 다시 시작과 그만할래를 듣게 하고(F16.9, F16.12), 내가 말한 시간은 [reduce] 가 세지 않는다.
     * 끊김으로 멈췄으면 보낼 연결이 없다.
     *
     * 스피커 모드는 목소리 주기가 없는 조각을 말소리로 치지 않는다(F20.3). 코치가 말하는 중에는 조각을 [bargeIn] 에 붙잡아 두고,
     * 내 말소리가 이어져 끊을 때가 되면 재생을 멈추고 붙잡은 조각부터 보낸다. 서버는 그 말소리를 듣고 끼어듦으로 처리한다(F20.4).
     * 끊기 전에 코치 말이 끝나면 붙잡은 조각을 먼저 보내고 다음 조각을 보낸다.
     */
    private suspend fun forwardMic() {
        var silence = Duration.INFINITE
        var spoken = Duration.ZERO

        suspend fun forward(pcm: ByteArray, speech: Boolean) {
            val length = pcm.length()
            if (speech) {
                link.sendAudio(pcm)
                silence = Duration.ZERO
                spoken += length
                speaking = true
                lastSpeechAt = clock.now()
            } else if (silence.isFinite()) {
                link.sendAudio(pcm)
                silence += length
                if (silence >= SPEECH_END_GRACE) {
                    link.sendAudioEnd()
                    silence = Duration.INFINITE
                    speaking = false
                    dispatch(SpeakingEvent.UserTalked(spoken))
                    spoken = Duration.ZERO
                }
            }
        }

        audio.mic.catch { dispatch(SpeakingEvent.Pause(clock.now())) }.collect { chunk ->
            val state = _state.value ?: return@collect
            if (state.disconnected) return@collect
            if (state.route == AudioRoute.Earphones) return@collect forward(chunk.pcm, chunk.speech)
            if (state.status != SpeakingStatus.CoachSpeaking) {
                // 코치 말 끝에 겹쳐 시작한 대답의 앞부분이다. 버리면 첫 낱말이 빠진다.
                bargeIn.drain().forEach { forward(it, speech = true) }
                return@collect forward(chunk.pcm, chunk.voiced)
            }
            if (!plan.setup.bargeIn) return@collect
            val held = bargeIn.hold(chunk.pcm, chunk.voiced, chunk.pcm.length()) ?: return@collect
            // 서버가 턴 끝을 아직 안 보냈으면 그 턴의 남은 음성이 더 온다.
            dropping = waiting?.isActive != true
            stopTurn()
            held.forEach { forward(it, speech = true) }
        }
    }

    private fun ByteArray.length() = (size / 2).seconds / DuplexAudio.MIC_SAMPLE_RATE

    private companion object {
        const val NONE = "none"
    }
}
