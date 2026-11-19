package io.github.nosorae.aiopic.apps.opic.shared

import io.github.nosorae.aiopic.engine.answer.analysis.AnalysisJobs
import io.github.nosorae.aiopic.engine.review.CardStore
import io.github.nosorae.aiopic.engine.speaking.SpeakingEvent
import io.github.nosorae.aiopic.engine.speaking.SpeakingLink
import io.github.nosorae.aiopic.engine.speaking.SpeakingProfile
import io.github.nosorae.aiopic.engine.speaking.SpeakingSession
import io.github.nosorae.aiopic.engine.speaking.SpeakingSetup
import io.github.nosorae.aiopic.engine.speaking.SpeakingState
import io.github.nosorae.aiopic.engine.speaking.SpeakingStatus
import io.github.nosorae.aiopic.engine.speaking.SpeakingStore
import io.github.nosorae.aiopic.libs.duplexaudio.api.AudioInterruption
import io.github.nosorae.aiopic.libs.duplexaudio.api.DuplexAudio
import io.github.nosorae.aiopic.ui.featurespeaking.PracticeCommand
import io.github.nosorae.aiopic.ui.featurespeaking.PracticeControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.getString
import kotlin.time.Clock

/**
 * 지금 도는 말하기 연습 하나(ADR 0038, design.md 12.2). 앱 범위에 하나 두고 Android 포그라운드 서비스와 iOS 백그라운드 오디오가 붙잡는다.
 * 화면은 세션을 만들지 않고 [state] 를 지켜보며 명령만 넘긴다([PracticeControl]). 오디오 끼어듦도 여기서 일시정지로 넣는다(F16.14).
 */
class RunningPractice(
    private val newSession: suspend (CoroutineScope) -> SpeakingSession,
    audio: DuplexAudio,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : PracticeControl {
    private val session = MutableStateFlow<SpeakingSession?>(null)

    /** 끼어듦으로 멈췄다. 끼어듦이 끝나면 이 경우에만 다시 시작한다. 사용자가 명령을 넣으면 지운다. */
    private var interruptedPause = false

    /** 시작 전에는 null 이고, 끝나면 끝난 마지막 상태가 다음 연습을 시작할 때까지 남는다. */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val state: Flow<SpeakingState?> = session.flatMapLatest { it?.state ?: flowOf(null) }

    /** 코치가 말하는 중이면 코치 목소리, 아니면 내 목소리 크기. */
    override val level: Flow<Float> = combine(state, audio.micLevel, audio.playbackLevel) { practice, mic, playback -> if (practice?.status == SpeakingStatus.CoachSpeaking) playback else mic }

    val current: SpeakingState? get() = session.value?.state?.value

    /** 상태가 바뀔 때 낼 효과음(F16.19). 소리는 플랫폼이 낸다. */
    val chimes: Flow<Chime> = flow {
        var before: SpeakingState? = null
        state.collect { after ->
            chime(before, after)?.let { emit(it) }
            before = after
        }
    }

    init {
        scope.launch { audio.interruptions.collect(::interrupted) }
    }

    /** 화면이 보일 때만 부른다(F16.4). 연습이 돌고 있으면 아무것도 하지 않는다. */
    override suspend fun start(id: String, setup: SpeakingSetup) {
        if (current?.finished == false) return
        val next = newSession(scope)
        next.start(id, setup)
        if (next.state.value == null) return
        interruptedPause = false
        session.value = next
    }

    override suspend fun command(command: PracticeCommand) {
        val running = session.value ?: return
        interruptedPause = false
        running.send(
            when (command) {
                PracticeCommand.Pause -> SpeakingEvent.Pause(clock.now())
                PracticeCommand.Resume -> SpeakingEvent.Resume
                PracticeCommand.Finish -> SpeakingEvent.Finish
                PracticeCommand.Toggle -> if (current?.status == SpeakingStatus.Paused) SpeakingEvent.Resume else SpeakingEvent.Pause(clock.now())
            },
        )
    }

    /**
     * 끼어듦이 시작되면 멈추고, 끼어듦으로 멈췄던 연습만 끝날 때 다시 시작한다. 다시 시작하면 코치가 이어서 할지 한 번 묻는다(F16.14).
     * 이어폰이 빠지면 멈추되 사용자가 다시 시작할 때까지 둔다. 뒤이은 끼어듦이 끝나도 스피커로 다시 시작하지 않는다.
     */
    internal suspend fun interrupted(interruption: AudioInterruption) {
        val running = session.value ?: return
        val paused = current?.status == SpeakingStatus.Paused
        when (interruption) {
            AudioInterruption.Began -> if (!paused) {
                interruptedPause = true
                running.send(SpeakingEvent.Pause(clock.now()))
            }

            AudioInterruption.Unplugged -> {
                interruptedPause = false
                if (!paused) running.send(SpeakingEvent.Pause(clock.now()))
            }

            AudioInterruption.Ended -> {
                if (interruptedPause && paused) running.send(SpeakingEvent.Resume)
                interruptedPause = false
            }
        }
    }
}

/**
 * 이어폰 버튼은 한 번 누르기만 받는다(F16.10). 앞 누르기 뒤 [window] 밀리초 안에 다시 눌리면 두 번 누르기의 일부라 버린다.
 * 버린 누르기도 앞 누르기로 쳐서 세 번 누르기도 첫 누르기만 남는다.
 */
class SinglePress(private val window: Long) {
    private var last: Long? = null

    fun accept(at: Long): Boolean {
        val single = last.let { it == null || at - it >= window }
        last = at
        return single
    }
}

/**
 * 진행 알림(F16.10)에 띄울 값. [toggle] 버튼은 [toggleCommand] 를 넣고, 다른 버튼은 끝내기다.
 * [counting] 이면 대화 시간이 흐르는 중이라 알림이 지난 시간을 센다.
 */
data class PracticeNotice(val title: String, val text: String, val toggle: String, val toggleCommand: PracticeCommand, val finish: String, val counting: Boolean)

suspend fun SpeakingState.notice(): PracticeNotice {
    val paused = status == SpeakingStatus.Paused
    val (title, text, toggle) = when {
        disconnected -> Triple(Res.string.practice_dropped_title, Res.string.practice_dropped_text, Res.string.practice_reconnect)
        paused -> Triple(Res.string.practice_paused_title, Res.string.practice_paused_text, Res.string.practice_resume)
        else -> Triple(Res.string.practice_running_title, Res.string.practice_running_text, Res.string.practice_pause)
    }
    return PracticeNotice(getString(title), getString(text), getString(toggle), if (paused) PracticeCommand.Resume else PracticeCommand.Pause, getString(Res.string.practice_finish), counting = !paused)
}

/** 앱이 만드는 연습 세션. 끝내면 연습 정리 작업을 바로 넣는다(F16.11, F17.4). */
internal fun speakingSession(
    profile: SpeakingProfile,
    link: SpeakingLink,
    audio: DuplexAudio,
    store: SpeakingStore,
    cards: CardStore,
    jobs: AnalysisJobs,
    clock: Clock,
    timeZone: TimeZone,
    scope: CoroutineScope,
) = SpeakingSession(profile, link, audio, store, cards, clock, timeZone, scope, onFinished = jobs::enqueueCards)

/**
 * 알림 권한을 물을 때(#229, F16.3). 분석이 돌기 시작하거나 말하기 연습이 돌기 시작하면 true 다.
 * 분석 끝 알림, 연습 진행 알림, 정리 끝 알림이 이 권한을 쓴다. Android 와 iOS 앱 틀이 이 조건 하나로 묻는다.
 */
internal fun notificationAskMoments(analyzing: Flow<Boolean>, practice: Flow<SpeakingState?>): Flow<Boolean> = merge(analyzing, practice.map { it?.finished == false })

/** 처음 알림 권한을 물을 때까지 기다린다. */
internal suspend fun AppGraph.awaitNotificationAsk() {
    notificationAskMoments(opicStore.analyzing(), runningPractice.state).first { it }
}
