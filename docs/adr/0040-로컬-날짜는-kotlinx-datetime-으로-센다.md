# 0040. 로컬 날짜는 kotlinx-datetime 으로 센다

- 상태: 채택
- 날짜: 2026-11-19

## 배경

표현 복습은 하루 단위로 센다. 오늘 복습한 수와 오늘 새로 넣은 수로 오늘 복습할 카드를 자르고(F18.2), 복습한 날을 8주 남겨 이번 주 복습한 날 수를 보인다(F18.9). 여기서 하루는 기기 시간대의 날짜다. 서울에서 새벽 1시에 한 복습은 UTC 로는 전날이지만 사용자에게는 오늘이다.

공통 코드에는 시각(`kotlin.time.Instant`)만 있고 시간대와 날짜가 없다.

## 후보

1. `kotlinx-datetime` 을 들여 `Instant.toLocalDateTime(timeZone).date.toEpochDays()` 로 센다
2. 플랫폼마다 지금 시간대의 UTC 차이(초)를 돌려주는 구현을 두고 `(밀리초 + 차이) / 하루` 를 내린다
3. 날짜 계산을 앱 층에 맡기고 엔진은 하루 시작 시각만 받는다

## 결정

1을 고른다.

- 날짜는 1970-01-01 을 0 으로 센 로컬 날짜 번호(`Long`)로 쓴다. `review_day.day` 도 이 번호다
- 시각을 날짜 번호로 바꾸는 일은 `engine/review` 의 `Instant.localDay(timeZone)` 하나가 한다
- 시간대는 `TimeZone` 으로 주입받는다. 앱은 `TimeZone.currentSystemDefault()` 를 넘기고 테스트는 `TimeZone.of("Asia/Seoul")` 처럼 고정한다

## 이유

2는 Android 와 iOS 구현을 따로 짜고 플랫폼마다 테스트해야 한다. 하나의 차이 값으로 날짜를 내리면 서머타임이 바뀌는 날의 날짜 경계가 한 시간 어긋난다. 1은 두 줄이고 공통 코드에서 끝나며 서머타임을 시간대 규칙대로 처리한다.

3은 엔진의 오늘 복습 고르기와 복습한 날 저장이 하루 경계를 몰라서, 같은 계산이 화면, 알림, 위젯(F19)마다 앱 층에 퍼진다.

## 결과

`engine/review` 가 `kotlinx-datetime` 을 api 의존으로 둔다. 날짜를 쓰는 다른 모듈(복습 알림 시각, 위젯)도 같은 라이브러리와 `localDay` 를 쓴다. 기기 시간대가 앱이 도는 중에 바뀌면 다음에 저장소를 만들 때부터 새 시간대로 센다.

## 근거

- [로컬 날짜 번호와 오늘 복습 고르기](../../engine/review/src/commonMain/kotlin/io/github/nosorae/aiopic/engine/review/ReviewQueue.kt)
- [카드 저장소](../../engine/review/src/commonMain/kotlin/io/github/nosorae/aiopic/engine/review/CardStore.kt)
