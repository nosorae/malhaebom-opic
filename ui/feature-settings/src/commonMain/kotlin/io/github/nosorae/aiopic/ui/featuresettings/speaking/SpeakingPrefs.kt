package io.github.nosorae.aiopic.ui.featuresettings.speaking

import io.github.nosorae.aiopic.engine.review.ReviewAmount
import kotlinx.datetime.LocalTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** 말 끝 기다림(F16.17). 코치가 이만큼 조용하면 말이 끝났다고 본다. */
enum class EndWait(val silence: Duration) { Short(700.milliseconds), Normal(1.seconds), Long(1500.milliseconds) }

/**
 * 설정 탭의 말하기 연습 묶음과 복습 묶음(F19.1). 처음 값은 요구사항이 정한 값이다(F16.2, F16.8, F16.17, F16.22, F18.7, F19.2).
 * [minutes] 는 시작 시트에 먼저 골라 둘 연습 시간이고 10, 20, 30 가운데 하나다.
 */
data class SpeakingPrefs(
    val minutes: Int = 20,
    val endWait: EndWait = EndWait.Normal,
    val bargeIn: Boolean = true,
    val grammarNudge: Boolean = false,
    val oralReview: Boolean = true,
    val amount: ReviewAmount = ReviewAmount.Normal,
    val reminderOn: Boolean = false,
    val reminderAt: LocalTime = LocalTime(20, 0),
)

/** 고를 수 있는 기본 연습 시간(분). 시작 시트와 같다(F16.2). */
val PRACTICE_MINUTES = listOf(10, 20, 30)

/** 말하기 연습과 복습 설정을 기기에 남긴다. 앱 층이 플랫폼 설정 저장소로 구현하고 시작 시트, 복습, 알림이 같은 값을 읽는다. */
interface SpeakingPrefsStore {
    suspend fun speakingPrefs(): SpeakingPrefs

    suspend fun saveSpeakingPrefs(prefs: SpeakingPrefs)
}
