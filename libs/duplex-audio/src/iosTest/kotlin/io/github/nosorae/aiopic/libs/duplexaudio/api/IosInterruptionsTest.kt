package io.github.nosorae.aiopic.libs.duplexaudio.api

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import platform.AVFAudio.AVAudioEngineConfigurationChangeNotification
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
import platform.darwin.NSObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** 전화 같은 끼어듦의 시작과 끝, 이어폰 빠짐을 낸다(F16.14). 끼어듦 동안에는 설정이 바뀌어도 마이크를 다시 열지 않는다. */
@OptIn(ExperimentalCoroutinesApi::class)
class IosInterruptionsTest {
    @Test
    fun `F16_14 끼어듦 시작과 끝과 이어폰 빠짐을 내고 이어폰 끼움은 버린다`() = runTest {
        val received = mutableListOf<AudioInterruption>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { IosDuplexAudio().interruptions.take(3).toList(received) }

        post(AVAudioSessionInterruptionNotification, AVAudioSessionInterruptionTypeKey, AVAudioSessionInterruptionTypeBegan)
        post(AVAudioSessionInterruptionNotification, AVAudioSessionInterruptionTypeKey, AVAudioSessionInterruptionTypeEnded)
        post(AVAudioSessionRouteChangeNotification, AVAudioSessionRouteChangeReasonKey, AVAudioSessionRouteChangeReasonNewDeviceAvailable)
        post(AVAudioSessionRouteChangeNotification, AVAudioSessionRouteChangeReasonKey, AVAudioSessionRouteChangeReasonOldDeviceUnavailable)
        job.join()

        assertEquals(listOf(AudioInterruption.Began, AudioInterruption.Ended, AudioInterruption.Unplugged), received)
    }

    @Test
    fun `F16_14 끼어듦 동안 설정이 바뀌어도 마이크를 다시 열지 않고 끝나면 열며 못 열었으면 다음 알림에 또 연다`() {
        val engine = NSObject()
        var reopened = 0
        val reopener = Reopener(engine, running = { false }) { reopened++ }

        post(AVAudioSessionInterruptionNotification, AVAudioSessionInterruptionTypeKey, AVAudioSessionInterruptionTypeBegan)
        NSNotificationCenter.defaultCenter.postNotificationName(AVAudioEngineConfigurationChangeNotification, engine)
        val during = reopened
        post(AVAudioSessionInterruptionNotification, AVAudioSessionInterruptionTypeKey, AVAudioSessionInterruptionTypeEnded)
        NSNotificationCenter.defaultCenter.postNotificationName(AVAudioEngineConfigurationChangeNotification, engine)
        reopener.remove()

        assertEquals(0 to 2, during to reopened)
    }

    private fun post(name: String?, key: String?, value: ULong) = NSNotificationCenter.defaultCenter.postNotificationName(name!!, null, mapOf<Any?, Any?>(key to NSNumber(unsignedLongLong = value)))
}
