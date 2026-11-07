package io.github.nosorae.aiopic.ui.design

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CompletableDeferred

@Composable
actual fun rememberMicPermission(): suspend () -> Boolean {
    val context = LocalContext.current
    val answer = remember { arrayOfNulls<CompletableDeferred<Boolean>>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> answer[0]?.complete(granted) }
    return remember(context, launcher) {
        suspend {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                true
            } else {
                CompletableDeferred<Boolean>().also {
                    answer[0] = it
                    launcher.launch(Manifest.permission.RECORD_AUDIO)
                }.await()
            }
        }
    }
}

@Composable
actual fun rememberOpenAppSettings(): () -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) }
    }
}
