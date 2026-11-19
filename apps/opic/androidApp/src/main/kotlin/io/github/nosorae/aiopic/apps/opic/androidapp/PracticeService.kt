package io.github.nosorae.aiopic.apps.opic.androidapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.view.KeyEvent
import android.view.ViewConfiguration
import io.github.nosorae.aiopic.apps.opic.shared.Chime
import io.github.nosorae.aiopic.apps.opic.shared.PracticeCommand
import io.github.nosorae.aiopic.apps.opic.shared.RunningPractice
import io.github.nosorae.aiopic.apps.opic.shared.notice
import io.github.nosorae.aiopic.engine.speaking.SpeakingState
import io.github.nosorae.aiopic.engine.speaking.SpeakingStatus
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 말하기 연습이 도는 동안 앱 프로세스를 붙잡는 마이크 포그라운드 서비스(ADR 0038). 세션은 그래프의 [RunningPractice] 가 들고,
 * 이 서비스는 진행 알림 버튼과 이어폰 버튼을 같은 명령으로 넘긴다(F16.10). 연습이 끝나면 스스로 내려간다.
 */
class PracticeService : Service() {
    private val scope = MainScope()
    private val practice: RunningPractice get() = (application as OpicApplication).graph.runningPractice
    private lateinit var media: MediaSession
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "말하기 연습", NotificationManager.IMPORTANCE_LOW))
        // 이어폰 한 번 누르기만 쓴다(F16.10). 기본 처리(두 번 누르면 다음 곡)를 막으려고 버튼 키를 모두 여기서 받는다.
        media = MediaSession(this, "practice").apply {
            setCallback(
                object : MediaSession.Callback() {
                    override fun onMediaButtonEvent(intent: Intent): Boolean {
                        val key = mediaKey(intent)?.takeIf { it.keyCode in BUTTON_KEYS } ?: return super.onMediaButtonEvent(intent)
                        if (key.action == KeyEvent.ACTION_UP && key.eventTime - key.downTime < ViewConfiguration.getLongPressTimeout()) send(PracticeCommand.Toggle)
                        return true
                    }
                },
            )
            setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY_PAUSE).setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
            isActive = true
        }
        // 알림은 일시정지, 다시 시작, 끊김처럼 상태가 바뀔 때만 고친다(F16.10). 끝나면 내려간다.
        scope.launch {
            practice.state.map { it?.takeUnless(SpeakingState::finished)?.let { state -> (state.status == SpeakingStatus.Paused) to state.disconnected } }
                .distinctUntilChanged()
                .collect { key ->
                    val state = practice.current
                    if (key == null || state == null) return@collect stopSelf()
                    show(state)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { send(PracticeCommand.valueOf(it)) }
        // 시작하고 5초 안에 포그라운드로 올려야 한다. 문구는 곧 상태 알림이 바꾼다.
        if (!foreground) {
            val placeholder = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(ID, placeholder, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(ID, placeholder)
            foreground = true
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        media.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun send(command: PracticeCommand) {
        scope.launch { practice.command(command) }
    }

    private suspend fun show(state: SpeakingState) {
        val notice = state.notice()
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // 지난 시간은 대화 시간이다. 일시정지 동안 늘지 않아 세지 않는다(F16.13).
            .setShowWhen(notice.counting)
            .setUsesChronometer(notice.counting)
            .setWhen(System.currentTimeMillis() - state.talkTime.inWholeMilliseconds)
            .setContentIntent(PendingIntent.getActivity(this, 0, packageManager.getLaunchIntentForPackage(packageName), PendingIntent.FLAG_IMMUTABLE))
            .addAction(action(notice.toggle, notice.toggleCommand))
            .addAction(action(notice.finish, PracticeCommand.Finish))
            .setStyle(Notification.MediaStyle().setMediaSession(media.sessionToken).setShowActionsInCompactView(0, 1))
            .build()
        getSystemService(NotificationManager::class.java).notify(ID, notification)
    }

    private fun action(label: String, command: PracticeCommand): Notification.Action {
        val intent = Intent(this, PracticeService::class.java).setAction(command.name)
        return Notification.Action.Builder(null, label, PendingIntent.getService(this, command.ordinal, intent, PendingIntent.FLAG_IMMUTABLE)).build()
    }

    companion object {
        private const val CHANNEL = "practice"
        private const val ID = 2881
        private val BUTTON_KEYS = setOf(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE)

        /** 연습이 시작되면 앱이 부른다. 화면이 보일 때만 시작하므로 포그라운드 서비스를 띄울 수 있다(F16.4). */
        fun start(context: Context) = context.startForegroundService(Intent(context, PracticeService::class.java))

        private fun mediaKey(intent: Intent): KeyEvent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
    }
}

/** 효과음(F16.19)을 알림 소리 용도로 낸다. 이어폰이 있으면 이어폰으로 나간다. 소리마다 짧은 트랙 하나를 만들어 다시 쓴다. */
internal class AndroidChimes {
    private val tracks = mutableMapOf<Chime, AudioTrack>()

    fun play(chime: Chime) {
        val track = tracks.getOrPut(chime) { track(chime.pcm()) }
        track.stop()
        track.reloadStaticData()
        track.play()
    }

    private fun track(pcm: ByteArray) = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(Chime.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setTransferMode(AudioTrack.MODE_STATIC)
        .setBufferSizeInBytes(pcm.size)
        .build()
        .apply { write(pcm, 0, pcm.size) }
}
