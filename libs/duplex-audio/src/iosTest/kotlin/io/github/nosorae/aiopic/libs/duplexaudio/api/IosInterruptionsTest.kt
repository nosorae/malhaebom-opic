package io.github.nosorae.aiopic.libs.duplexaudio.api

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonNewDeviceAvailable
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import kotlin.test.Test
import kotlin.test.assertEquals

/** 전화 같은 끼어듦과 이어폰 빠짐을 끼어듦 시작과 끝으로 낸다(F16.14). */
class IosInterruptionsTest {
    @Test
    fun `F16_14 끼어듦 시작과 끝, 이어폰 빠짐을 내고 이어폰 끼움은 버린다`() = runTest {
        val received = mutableListOf<AudioInterruption>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { IosDuplexAudio().interruptions.take(3).toList(received) }

        post(AVAudioSessionInterruptionNotification, AVAudioSessionInterruptionTypeKey, AVAudioSessionInterruptionTypeBegan)
        post(AVAudioSessionInterruptionNotification, AVAudioSessionInterruptionTypeKey, AVAudioSessionInterruptionTypeEnded)
        post(AVAudioSessionRouteChangeNotification, AVAudioSessionRouteChangeReasonKey, AVAudioSessionRouteChangeReasonNewDeviceAvailable)
        post(AVAudioSessionRouteChangeNotification, AVAudioSessionRouteChangeReasonKey, AVAudioSessionRouteChangeReasonOldDeviceUnavailable)
        job.join()

        assertEquals(listOf(AudioInterruption.Began, AudioInterruption.Ended, AudioInterruption.Began), received)
    }

    private fun post(name: String?, key: String?, value: ULong) =
        NSNotificationCenter.defaultCenter.postNotificationName(name!!, null, mapOf<Any?, Any?>(key to NSNumber(unsignedLongLong = value)))
}
