package io.github.nosorae.aiopic.engine.coach

import io.github.nosorae.aiopic.libs.duplexaudio.api.DuplexAudio
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 코치 세션 하나를 돌린다. 일어난 일을 [reduce] 로 상태에 반영하고 저장한 뒤, 상태에 맞게 연결을 열고 닫는다.
 * 대화 단계(경험, 표현, 피드백)마다 메모를 담은 지시로 새로 연결하고, 리허설과 일시정지와 끝에서는 연결을 닫는다.
 * 대화 시간은 연결이 열린 동안만 [clock] 으로 잰다.
 */
class LiveCoach(
    private val profile: CoachProfile,
    private val transport: LiveCoachTransport,
    private val audio: DuplexAudio,
    private val store: CoachStore,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<CoachState?>(null)

    /** 시작하거나 열기 전에는 null 이다. */
    val state: StateFlow<CoachState?> = _state.asStateFlow()

    private val lock = Mutex()
    private var connection: Job? = null
    private var connectedPhase: CoachPhase? = null

    suspend fun start(id: String, questionId: String, question: String, targetGrade: String) = lock.withLock { set(CoachState(id, questionId, question, targetGrade, clock.now())) }

    /** 저장된 세션을 되살려 이어 간다. 진행 중이던 세션이면 바로 다시 연결한다. 없으면 false. */
    suspend fun open(id: String): Boolean = lock.withLock {
        val saved = store.load(id) ?: return@withLock false
        set(saved)
        true
    }

    /**
     * 사용자가 한 일을 넣는다. 다음 단계, 잠깐 생각(F11.6)과 앱이 뒤로 갈 때(F11.8)의 일시정지, 다시 연결, 리허설 받아쓰기(F13),
     * 한 번 더 리허설(F13.3), 메모와 표현 카드 고치기(F12.2)다. 바뀐 상태에 맞게 연결을 열고 닫는다.
     */
    suspend fun send(event: CoachEvent) = dispatch(event)

    private suspend fun dispatch(event: CoachEvent) = lock.withLock {
        val before = _state.value ?: return@withLock
        val after = reduce(before, event)
        store.append(before.id, event)
        if (after != before) set(after)
    }

    private suspend fun set(state: CoachState) {
        store.save(state)
        _state.value = state
        sync(state)
    }

    /** 대화 단계에서 진행 중이고 끊기지 않았으면 그 단계 연결 하나를 둔다. 단계가 바뀌면 앞 연결이 닫힌 뒤 새로 연다. */
    private fun sync(state: CoachState) {
        val live = state.status == CoachStatus.Active && state.disconnected == null && state.phase in LIVE_PHASES
        if (live && connectedPhase == state.phase && connection?.isActive == true) return
        val old = connection
        old?.cancel()
        connection = if (live) {
            scope.launch {
                old?.join()
                // 예외가 scope(화면의 viewModelScope)까지 가면 앱이 꺼진다. 끊김으로 남겨 다시 연결할 수 있게 한다.
                try {
                    talk(state)
                } catch (e: Exception) {
                    ensureActive()
                    dispatch(CoachEvent.Dropped(TransportEvent.Disconnected.NETWORK))
                }
            }
        } else {
            null
        }
        connectedPhase = state.phase.takeIf { live }
    }

    private suspend fun talk(state: CoachState) = coroutineScope {
        val handle = state.resumeHandle?.takeIf { state.handleAt != null && clock.now() - state.handleAt < RESUME_LIMIT }
        launch { forwardMic() }
        launch {
            // ponytail: 1초마다 잰다. 일시정지할 때 1초 미만은 버린다.
            while (true) {
                val from = clock.now()
                delay(1.seconds)
                dispatch(CoachEvent.TimePassed(clock.now() - from))
            }
        }
        // 코치가 단계를 넘기면 그 턴(넘긴다는 말과 사용량)을 다 받은 뒤 넘긴다. 바로 닫으면 그 말과 사용량을 잃는다.
        var turnEnd: CompletableJob? = null
        transport.connect(instruction(profile, state), profile.tools, handle).collect { event ->
            when (event) {
                is TransportEvent.Audio -> audio.play(event.pcm)

                TransportEvent.Interrupted -> audio.flush()

                is TransportEvent.UserSaid -> dispatch(CoachEvent.Heard(Speaker.User, event.text))

                is TransportEvent.CoachSaid -> dispatch(CoachEvent.Heard(Speaker.Coach, event.text))

                is TransportEvent.ToolCall -> {
                    // 버린 호출에도 답해야 모델이 기다리지 않고 이어 말한다.
                    val error = toolError(event.name, event.args)
                    transport.sendToolResponse(event.id, event.name, buildJsonObject { if (error == null) put("ok", true) else put("error", error) })
                    val called = CoachEvent.ToolCalled(event.name, event.args)
                    if (event.name != CoachTools.ADVANCE_PHASE) {
                        dispatch(called)
                    } else if (turnEnd == null) {
                        val end = Job().also { turnEnd = it }
                        launch {
                            withTimeoutOrNull(ADVANCE_WAIT) { end.join() }
                            dispatch(called)
                        }
                    }
                }

                TransportEvent.TurnComplete -> turnEnd?.complete()

                is TransportEvent.Usage -> dispatch(CoachEvent.Used(event.usage))

                is TransportEvent.ResumeHandle -> dispatch(CoachEvent.HandleReceived(event.handle, clock.now()))

                is TransportEvent.Disconnected -> dispatch(CoachEvent.Dropped(event.reason))
            }
        }
    }

    /**
     * 말소리 조각과 말이 멈춘 뒤 [SPEECH_END_GRACE] 동안의 침묵 조각을 보내고, 그 뒤 오디오 끝을 한 번 보낸다.
     * 다음 말소리까지는 보내지 않는다. 생각하는 동안의 침묵을 보내지 않아야 입력 토큰이 늘지 않는다.
     */
    private suspend fun forwardMic() {
        // 말이 멈춘 뒤 보낸 침묵 길이. 오디오 끝을 보냈으면 INFINITE 다.
        var silence = Duration.INFINITE
        audio.mic.catch { dispatch(CoachEvent.Dropped(TransportEvent.Disconnected.MIC)) }.collect { chunk ->
            if (chunk.speech) {
                transport.sendAudio(chunk.pcm)
                silence = Duration.ZERO
            } else if (silence.isFinite()) {
                transport.sendAudio(chunk.pcm)
                silence += (chunk.pcm.size / 2).seconds / DuplexAudio.MIC_SAMPLE_RATE
                if (silence >= SPEECH_END_GRACE) {
                    transport.sendAudioEnd()
                    silence = Duration.INFINITE
                }
            }
        }
    }

    private companion object {
        val LIVE_PHASES = setOf(CoachPhase.Explore, CoachPhase.Express, CoachPhase.Feedback)

        /** 단계를 넘긴 턴이 끝나기를 기다리는 상한. 넘긴다는 말 한 턴은 몇 초라 넉넉히 두고, 턴 끝이 안 와도 사용자가 오래 기다리지 않게 한다. */
        val ADVANCE_WAIT = 8.seconds

        /**
         * 서버 음성 감지는 말 뒤의 침묵을 들어야 말이 끝났다고 보고 코치가 답한다. 침묵 조각 하나(40ms)에 바로 오디오 끝을 보내면
         * 문장 사이 숨 고르기에도 말이 끊긴다. 1초는 숨 고르기보다 길고 코치 답이 늦다고 느끼기 전이다.
         */
        // ponytail: 실기기에서 말 끝 판단이 이르거나 늦으면 이 값만 고친다.
        val SPEECH_END_GRACE = 1.seconds
    }
}
