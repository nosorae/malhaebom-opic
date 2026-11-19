package io.github.nosorae.aiopic.ui.featurespeaking

import dev.zacsweers.metro.Inject

/** 연습 중 화면 버튼을 알림 버튼, 이어폰 버튼과 같은 명령으로 넘긴다(F16.10, ADR 0038). */
@Inject
class ControlPracticeUseCase(private val control: PracticeControl) {
    suspend operator fun invoke(command: PracticeCommand) = control.command(command)
}
