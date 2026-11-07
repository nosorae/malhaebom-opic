package io.github.nosorae.aiopic.ui.featurelive

import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.libs.recorder.api.Recorder

/** 앞 녹음이 남았으면(녹음 중 앱이 꺼진 경우) 지우고 새로 녹음한다. 녹음기는 폴더에 조각이 있으면 이어 쓰기 때문이다. */
@Inject
class StartRehearsalUseCase(private val recorder: Recorder, private val files: RehearsalFiles) {
    suspend operator fun invoke(sessionId: String): RehearsalError? {
        val directory = files.directory(sessionId)
        files.clear(directory)
        return recorder.start(directory)?.toRehearsalError()
    }
}
