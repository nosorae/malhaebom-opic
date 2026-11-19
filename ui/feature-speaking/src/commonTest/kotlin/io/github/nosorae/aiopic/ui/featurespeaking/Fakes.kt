package io.github.nosorae.aiopic.ui.featurespeaking

import io.github.nosorae.aiopic.engine.answer.store.OpicDatabase
import io.github.nosorae.aiopic.engine.review.ReviewAmount
import io.github.nosorae.aiopic.engine.speaking.AudioRoute
import io.github.nosorae.aiopic.engine.speaking.SpeakingSetup
import io.github.nosorae.aiopic.engine.speaking.SpeakingState
import io.github.nosorae.aiopic.ui.featureexpressions.ReviewBatch
import io.github.nosorae.aiopic.ui.featureexpressions.ReviewSettings
import io.github.nosorae.aiopic.ui.featurelive.LiveConsent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** 플랫폼마다 메모리 DB 를 만든다. [context] 에 테스트 디스패처를 주면 쿼리가 테스트 시계 위에서 돈다. */
expect fun inMemoryDatabase(context: CoroutineContext? = null): OpicDatabase

/** 앱 범위 연습 대신 받은 시작과 명령을 남기고, [state] 를 테스트가 직접 바꾼다. */
class FakePracticeControl : PracticeControl {
    override val state = MutableStateFlow<SpeakingState?>(null)
    override val level = MutableStateFlow(0f)
    val started = mutableListOf<Pair<String, SpeakingSetup>>()
    val sent = mutableListOf<PracticeCommand>()

    override suspend fun start(id: String, setup: SpeakingSetup) {
        started += id to setup
    }

    override suspend fun command(command: PracticeCommand) {
        sent += command
    }
}

class FakeSpeakingSettings(var minutes: Int = 20, var key: Boolean = true) : SpeakingSettings {
    override suspend fun defaultMinutes() = minutes

    override suspend fun hasKey() = key

    override suspend fun setup(planned: Duration, route: AudioRoute) = SpeakingSetup(planned, route, topics = listOf("cooking"))
}

class FakeLiveConsent(var given: Boolean = true) : LiveConsent {
    override suspend fun isLiveGiven() = given

    override suspend fun giveLive() {
        given = true
    }
}

class FakeReviewSettings : ReviewSettings {
    override suspend fun silent() = false

    override suspend fun setSilent(on: Boolean) = Unit

    override suspend fun reviewAmount() = ReviewAmount.Normal

    override suspend fun reviewBatch(): ReviewBatch? = null

    override suspend fun saveReviewBatch(batch: ReviewBatch) = Unit
}

class FixedClock(var now: Instant) : Clock {
    override fun now() = now
}
