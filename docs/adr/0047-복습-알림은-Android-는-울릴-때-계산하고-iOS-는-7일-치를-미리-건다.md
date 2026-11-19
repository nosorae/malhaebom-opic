# 0047. 복습 알림은 Android 는 울릴 때 계산하고 iOS 는 7일 치를 미리 건다

- 상태: 채택
- 날짜: 2026-11-20

## 배경

복습 알림은 하루 한 번 정한 시각에, 오늘 복습할 카드가 있고 그날 아직 복습하지 않았을 때만 보낸다(F19.3). 보낼지는 그 시각의 카드와 복습한 날로 정해진다. Android 는 알림 시각에 앱 코드를 돌릴 수 있지만 iOS 는 정해 둔 알림을 시스템이 띄울 뿐 그때 앱 코드를 돌리지 않는다(F19.4).

## 후보

1. 판단은 공통 코드 순수 함수 하나(`reviewReminderAt`)로 하고, Android 는 알람이 울릴 때 그 함수로 정한 뒤 다음 날 알람을 다시 건다. iOS 는 앞으로 7일의 알림 시각마다 같은 함수로 미리 계산해 건다
2. 두 플랫폼 모두 iOS 처럼 미리 계산해 건다
3. 두 플랫폼 모두 매일 같은 시각에 반복 알림을 걸고 조건은 보지 않는다

## 결정

1을 고른다.

- 시각 계산(`reminderTimes`)과 보낼지 판단(`reviewReminderAt`), iOS 미리 계산(`upcomingReviewReminders`)은 `apps/opic/shared` 공통 코드에 둔다
- Android 는 정확한 시각 알람 권한 없이 `setAndAllowWhileIdle` 로 건다. 울리면 앱이 화면에 없을 때만 띄우고 다음 날 시각을 다시 건다. 기기 재시작과 앱 업데이트 뒤에도 다시 건다
- iOS 는 카드, 복습한 날, 설정이 바뀌거나 앱이 앞으로 올 때 걸어 둔 복습 알림을 지우고 7일 치를 다시 건다. 미리 건 알림은 그 사이 복습하지 않는다고 보고 센다
- 알림을 누르면 두 플랫폼 모두 `malhaebom://review` 링크로 복습 화면을 연다
- 알림 권한은 다른 알림과 같은 조건 함수(`notificationAskMoments`)에 알림 켬을 더해 처음 한 번 묻는다
- 말하기 연습과 복습 설정 값은 기존 설정 저장소에 값마다 키 하나로 둔다

## 이유

2를 Android 에 쓰면 알림 시각 전에 연습 정리로 카드가 생겨도 앱을 다시 열기 전까지 반영되지 않는다. Android 는 울릴 때 DB 를 읽을 수 있으니 그때 읽는 쪽이 늘 맞다.

3은 할 카드가 없거나 이미 복습한 날에도 알림이 와서 F19.3 을 어긴다.

판단 함수가 공통 코드 하나라서 두 플랫폼의 조건이 같고, 시각과 카드 목록을 넣어 호스트 테스트로 확인한다(`ReviewReminderTest`).

## 결과

- iOS 는 7일 넘게 앱을 열지 않으면 알림이 멈춘다. 앱을 열면 다시 건다. 미리 건 알림의 장 수는 그 사이 복습하면 앱이 다시 계산할 때 고쳐진다
- Android 알림은 기기가 쉬는 중이면 몇 분 늦을 수 있다. 정확한 시각이 필요해지면 정확한 알람 권한을 더한다
- 기기 시간대가 앱이 도는 중에 바뀌면 다음 실행부터 새 시간대로 건다(ADR 0040 과 같다)

## 근거

- [알림 판단과 시각 계산](../../apps/opic/shared/src/commonMain/kotlin/io/github/nosorae/aiopic/apps/opic/shared/ReviewReminder.kt)
- [Android 알람과 알림](../../apps/opic/androidApp/src/main/kotlin/io/github/nosorae/aiopic/apps/opic/androidapp/ReviewReminders.kt)
- [iOS 미리 걸기](../../apps/opic/shared/src/iosMain/kotlin/io/github/nosorae/aiopic/apps/opic/shared/IosShell.kt)
- [설정 저장소](../../apps/opic/shared/src/commonMain/kotlin/io/github/nosorae/aiopic/apps/opic/shared/AppSettings.kt)
