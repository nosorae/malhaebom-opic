package io.github.nosorae.aiopic.ui.design

import androidx.compose.runtime.Composable

/**
 * 마이크 권한을 받는 함수를 돌려준다. 이미 받았으면 바로 true, 아니면 시스템 요청 창을 띄우고 사용자의 답을 돌려준다.
 * 모의고사, 한 문항 연습, 코치 대화가 녹음 전에 묻는다.
 */
@Composable
expect fun rememberMicPermission(): suspend () -> Boolean

/** 이 앱의 시스템 설정 화면을 여는 함수를 돌려준다. 권한을 한 번 거절하면 요청 창이 다시 뜨지 않아 설정에서 켜야 한다. */
@Composable
expect fun rememberOpenAppSettings(): () -> Unit
