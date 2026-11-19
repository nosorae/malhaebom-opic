package io.github.nosorae.aiopic.apps.opic.shared

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import platform.AVFAudio.AVAudioPlayer
import platform.Foundation.NSData
import platform.Foundation.create
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess

/**
 * 말하기 연습의 iOS 조작(F16.10)과 효과음(F16.19). 화면이 꺼져도 연습이 이어지는 것은 백그라운드 오디오 모드(Info.plist)와
 * 연습 동안 열려 있는 말하면서 듣는 오디오 세션 덕분이다. 이어폰 버튼은 원격 명령으로 받아 같은 명령 입구에 넣는다(ADR 0038).
 */
internal fun watchPractice(practice: RunningPractice) {
    val center = MPRemoteCommandCenter.sharedCommandCenter()
    val remotes = mapOf(center.togglePlayPauseCommand to PracticeCommand.Toggle, center.pauseCommand to PracticeCommand.Pause, center.playCommand to PracticeCommand.Resume)
    remotes.forEach { (remote, command) ->
        remote.addTargetWithHandler {
            MainScope().launch { practice.command(command) }
            MPRemoteCommandHandlerStatusSuccess
        }
    }
    // 연습 중일 때만 버튼을 켜고 잠금 화면에 연습 중임을 보인다. 지금 재생 중인 앱이어야 이어폰 버튼이 이 앱으로 온다.
    MainScope().launch {
        practice.state.map { it != null && !it.finished }.distinctUntilChanged().collect { running ->
            remotes.keys.forEach { it.enabled = running }
            MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = if (running) mapOf<Any?, Any?>(MPMediaItemPropertyTitle to getString(Res.string.practice_running_title)) else null
        }
    }
    MainScope().launch { practice.chimes.collect(::playChime) }
}

/** 재생이 끝나기 전에 풀리지 않게 마지막 효과음 플레이어를 들고 있는다. */
private var chimePlayer: AVAudioPlayer? = null

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun playChime(chime: Chime) {
    val wav = wav(chime.pcm(), Chime.SAMPLE_RATE)
    val data = wav.usePinned { NSData.create(bytes = it.addressOf(0), length = wav.size.toULong()) }
    chimePlayer = AVAudioPlayer(data = data, error = null).apply { play() }
}

/** 16비트 모노 PCM 앞에 WAV 머리(44바이트)를 붙인다. AVAudioPlayer 는 머리 없는 PCM 을 열지 못한다. */
internal fun wav(pcm: ByteArray, sampleRate: Int): ByteArray {
    val header = ByteArray(44)
    fun put(at: Int, value: Int, bytes: Int) = repeat(bytes) { header[at + it] = (value shr (8 * it)).toByte() }
    "RIFF".encodeToByteArray().copyInto(header, 0)
    put(4, 36 + pcm.size, 4)
    "WAVEfmt ".encodeToByteArray().copyInto(header, 8)
    put(16, 16, 4)
    put(20, 1, 2)
    put(22, 1, 2)
    put(24, sampleRate, 4)
    put(28, sampleRate * 2, 4)
    put(32, 2, 2)
    put(34, 16, 2)
    "data".encodeToByteArray().copyInto(header, 36)
    put(40, pcm.size, 4)
    return header + pcm
}
