package io.github.nosorae.aiopic.apps.opic.shared

import androidx.compose.runtime.Composable

/** 이어폰(유선, 블루투스)이 지금 연결돼 있는지 묻는 함수. 연습 시작 시트를 열 때 소리 환경의 처음 값을 고른다(F16.2, F20.1). */
@Composable
internal expect fun rememberEarphonesCheck(): () -> Boolean
