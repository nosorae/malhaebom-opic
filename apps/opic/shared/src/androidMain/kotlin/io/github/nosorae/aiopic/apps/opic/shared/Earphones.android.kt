package io.github.nosorae.aiopic.apps.opic.shared

import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

private val EARPHONES = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
)

@Composable
internal actual fun rememberEarphonesCheck(): () -> Boolean {
    val audio = LocalContext.current.getSystemService(AudioManager::class.java)
    return remember(audio) { { audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in EARPHONES } } }
}
