package io.github.nosorae.aiopic.apps.opic.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionPortBluetoothA2DP
import platform.AVFAudio.AVAudioSessionPortBluetoothHFP
import platform.AVFAudio.AVAudioSessionPortBluetoothLE
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.AVAudioSessionPortHeadphones
import platform.AVFAudio.currentRoute

private val EARPHONES = setOf(AVAudioSessionPortHeadphones, AVAudioSessionPortBluetoothA2DP, AVAudioSessionPortBluetoothHFP, AVAudioSessionPortBluetoothLE)

@Composable
internal actual fun rememberEarphonesCheck(): () -> Boolean = remember {
    { AVAudioSession.sharedInstance().currentRoute.outputs.filterIsInstance<AVAudioSessionPortDescription>().any { it.portType in EARPHONES } }
}
