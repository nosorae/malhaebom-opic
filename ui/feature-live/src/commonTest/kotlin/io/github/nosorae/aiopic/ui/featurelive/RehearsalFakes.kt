package io.github.nosorae.aiopic.ui.featurelive

import io.github.nosorae.aiopic.engine.answer.port.AnalysisResult
import io.github.nosorae.aiopic.engine.answer.port.Transcript
import io.github.nosorae.aiopic.libs.recorder.api.Recorder
import io.github.nosorae.aiopic.libs.recorder.api.RecordingFailure
import io.github.nosorae.aiopic.libs.recorder.api.StoppedRecording
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Duration

/** 파일을 쓰지 않는다. 시작할 때마다 조각 경로 하나를 더한다. 음량은 테스트가 [level] 에 넣은 값을 낸다. */
class FakeRecorder(var micAvailable: Boolean = true) : Recorder {
    val folders = mutableMapOf<String, MutableList<String>>()
    var recording: String? = null
    val level = MutableStateFlow(0f)
    override val levels = level

    override suspend fun start(directory: String): RecordingFailure? {
        if (!micAvailable) return RecordingFailure.MicUnavailable
        val chunks = folders.getOrPut(directory) { mutableListOf() }
        chunks += "$directory/${chunks.size}.m4a"
        recording = directory
        return null
    }

    override suspend fun stop(): StoppedRecording = StoppedRecording(chunks(checkNotNull(recording) { "녹음 중이 아니다" })).also { recording = null }

    override suspend fun chunks(directory: String): List<String> = folders[directory].orEmpty().toList()

    override suspend fun merge(chunks: List<String>, output: String): List<Duration> = emptyList()
}

/** 녹음 폴더는 [recorder] 의 것을 그대로 쓴다. 받아쓰기는 [results] 에서 차례로 꺼내고, 비면 마지막 값을 다시 준다. */
class FakeRehearsalFiles(private val recorder: FakeRecorder, vararg results: AnalysisResult<Transcript>) : RehearsalFiles {
    private val results = results.toMutableList()
    val sent = mutableListOf<List<String>>()

    override fun directory(sessionId: String) = "coach/$sessionId"

    override suspend fun transcribe(directory: String): AnalysisResult<Transcript> {
        sent += recorder.chunks(directory)
        return if (results.size > 1) results.removeAt(0) else results.single()
    }

    override suspend fun clear(directory: String) {
        recorder.folders.remove(directory)
    }
}

fun heard(text: String): AnalysisResult<Transcript> = AnalysisResult.Success(Transcript(text, emptyList()))
