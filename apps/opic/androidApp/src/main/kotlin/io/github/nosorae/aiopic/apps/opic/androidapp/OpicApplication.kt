package io.github.nosorae.aiopic.apps.opic.androidapp

import android.app.Application
import android.media.AudioManager
import io.github.nosorae.aiopic.apps.opic.shared.AppGraph
import io.github.nosorae.aiopic.apps.opic.shared.AppSettings
import io.github.nosorae.aiopic.apps.opic.shared.analysisProgress
import io.github.nosorae.aiopic.apps.opic.shared.createAppGraph
import io.github.nosorae.aiopic.apps.opic.shared.data.opicDatabase
import io.github.nosorae.aiopic.apps.opic.shared.data.recordingsRoot
import io.github.nosorae.aiopic.apps.opic.shared.debugGeminiClient
import io.github.nosorae.aiopic.apps.opic.shared.drainAndNotify
import io.github.nosorae.aiopic.apps.opic.shared.nextDayStart
import io.github.nosorae.aiopic.apps.opic.shared.registerDebugPanels
import io.github.nosorae.aiopic.apps.opic.shared.reviewReminderSchedule
import io.github.nosorae.aiopic.apps.opic.shared.reviewWidget
import io.github.nosorae.aiopic.apps.opic.shared.withDebugTools
import io.github.nosorae.aiopic.libs.debug.DebugTools
import io.github.nosorae.aiopic.libs.debug.install
import io.github.nosorae.aiopic.libs.duplexaudio.api.AndroidDuplexAudio
import io.github.nosorae.aiopic.libs.keyvault.api.KeystoreKeyVault
import io.github.nosorae.aiopic.libs.llmgemini.GeminiClient
import io.github.nosorae.aiopic.libs.recorder.api.AndroidRecorder
import io.github.nosorae.aiopic.libs.recorder.api.Recorder
import io.github.nosorae.aiopic.libs.tts.AndroidTts
import io.github.nosorae.aiopic.libs.workqueue.api.WorkManagerWorkQueue
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 앱 프로세스에 그래프 하나를 둔다. 화면이 없을 때 WorkManager 가 프로세스를 깨워도 분석 대기열이 처리기를 찾게
 * Activity 가 아니라 여기서 만든다(F5.1).
 */
open class OpicApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // 그래프가 스위치를 읽고 클라이언트를 만들기 전에 설치한다. 릴리스 APK 는 빈 모듈이라 아무 일도 안 한다.
        DebugTools.install(this)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        // 대기열이 그래프보다 먼저 만들어지므로 비우기는 부를 때 그래프에서 찾는다. 대기열은 DB 의 분석 작업 표를 비우기만 한다(#205).
        // 비우는 동안은 진행 알림을 달고, 앱이 화면에 없을 때 끝나면 리포트 알림을 띄운다(#229).
        AnalysisNotifications.createChannels(this)
        val workQueue = WorkManagerWorkQueue(
            context = this,
            drain = { graph.drainAndNotify(AnalysisNotifications::appVisible) { AnalysisNotifications.notify(this, it) } },
            progress = { graph.opicStore.analysisProgress(graph.clock).map { progress -> progress?.let { AnalysisNotifications.progress(this, it) } } },
        )
        graph = createAppGraph(
            settings = AppSettings(read = { prefs.getString(it, null) }, write = { key, value -> prefs.edit().putString(key, value).apply() }),
            keyVault = KeystoreKeyVault(this),
            database = opicDatabase(this),
            recordingsRoot = recordingsRoot(this),
            recorder = recorder(),
            tts = AndroidTts(this),
            workQueue = workQueue,
            duplexAudio = AndroidDuplexAudio(this),
            gemini = gemini(),
        )
        graph.registerDebugPanels(audio = ::audioRows)
        // 앱이 보내는 중에 죽었으면 진행 중으로 남은 작업을 대기로 돌리고 대기열을 깨운다.
        MainScope().launch { graph.analysisJobs.recover() }
        // 말하기 연습이 시작되면 서비스가 프로세스를 붙잡고(ADR 0038), 상태가 바뀔 때마다 효과음을 낸다(F16.19).
        val practice = graph.runningPractice
        MainScope().launch {
            practice.state.map { it != null && !it.finished }.distinctUntilChanged().collect { running -> if (running) PracticeService.start(this@OpicApplication) }
        }
        // 복습 알림을 켜거나 시각을 바꾸면 알람을 다시 건다(F19.3).
        ReviewReminders.createChannel(this)
        MainScope().launch { graph.reviewReminderSchedule().collect { ReviewReminders.arm(this@OpicApplication, it) } }
        // 복습을 끝내거나 연습 정리가 끝나 카드가 바뀌면 위젯을 다시 그린다(F19.7).
        MainScope().launch { graph.reviewWidget().collect { ReviewWidgets.draw(this@OpicApplication, it, graph.nextDayStart()) } }
        val chimes = AndroidChimes()
        MainScope().launch { practice.chimes.collect(chimes::play) }
    }

    /** 시나리오 테스트가 저장해 둔 응답을 돌려주는 클라이언트로 바꾼다. */
    protected open fun gemini(): GeminiClient = debugGeminiClient()

    /** 시나리오 테스트가 에뮬레이터의 조용한 마이크 대신 말소리 음량을 내게 바꾼다(#241). */
    protected open fun recorder(): Recorder = AndroidRecorder(this).withDebugTools()

    private fun audioRows(): List<Pair<String, String>> {
        val audio = getSystemService(AudioManager::class.java)
        return listOf(
            "mode" to audio.mode.toString(),
            "music active" to audio.isMusicActive.toString(),
            "outputs" to audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).joinToString { it.type.toString() },
            "inputs" to audio.getDevices(AudioManager.GET_DEVICES_INPUTS).joinToString { it.type.toString() },
        )
    }
}
