package io.github.nosorae.aiopic.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioApplication
import platform.AVFAudio.AVAudioApplicationRecordPermissionGranted
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import kotlin.coroutines.resume

@Composable
actual fun rememberMicPermission(): suspend () -> Boolean = remember {
    suspend {
        val app = AVAudioApplication.sharedInstance
        if (app.recordPermission == AVAudioApplicationRecordPermissionGranted) {
            true
        } else {
            suspendCancellableCoroutine { continuation ->
                AVAudioApplication.requestRecordPermissionWithCompletionHandler { granted -> continuation.resume(granted) }
            }
        }
    }
}

@Composable
actual fun rememberOpenAppSettings(): () -> Unit = remember {
    { NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let { UIApplication.sharedApplication.openURL(it, options = emptyMap<Any?, Any>(), completionHandler = null) } }
}
