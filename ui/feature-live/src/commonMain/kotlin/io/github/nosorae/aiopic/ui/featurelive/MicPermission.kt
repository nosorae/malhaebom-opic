package io.github.nosorae.aiopic.ui.featurelive

import androidx.compose.runtime.Composable

/**
 * 마이크 권한을 받는 함수를 돌려준다. 이미 받았으면 바로 true, 아니면 시스템 요청 창을 띄우고 사용자의 답을 돌려준다.
 * 실시간 음성 전송 동의(F15.1) 뒤 대화 화면에 들어올 때 묻는다.
 */
@Composable
internal expect fun rememberMicPermission(): suspend () -> Boolean

/** 이 앱의 시스템 설정 화면을 여는 함수를 돌려준다. 권한을 한 번 거절하면 요청 창이 다시 뜨지 않아 설정에서 켜야 한다. */
@Composable
internal expect fun rememberOpenAppSettings(): () -> Unit
