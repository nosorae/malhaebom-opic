package io.github.nosorae.aiopic.ui.featuresettings.speaking

import dev.zacsweers.metro.Inject

/** 저장된 값에 [change] 를 씌워 저장하고 저장한 값을 돌려준다. 값마다 저장 함수를 따로 두지 않는다. */
@Inject
class UpdateSpeakingPrefsUseCase(private val store: SpeakingPrefsStore) {
    suspend operator fun invoke(change: (SpeakingPrefs) -> SpeakingPrefs): SpeakingPrefs = change(store.speakingPrefs()).also { store.saveSpeakingPrefs(it) }
}
