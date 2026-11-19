# 0042. 출시 전이라 Room version 5 를 고쳐 쓴다

- 상태: 채택
- 날짜: 2026-11-19

## 배경

말하기 연습 요구사항을 고치면서(ADR 0041) 표에 열이 는다. `expression_card` 에 처음 봄, `speaking_session` 에 내가 말한 시간, 코치가 말한 시간, 주제 바꾼 횟수, 말로 복습 결과, 표현을 쓴 횟수, 넘긴 오류 수, 신고 수, `speaking_active` 에 주제, 다룬 질문, 마지막으로 물은 질문 위치, 말한 시간과 말로 복습 결과다(design.md 12.4).

Room version 5 와 4에서 5로 옮기는 SQL 은 이미 main 에 병합됐다(#286). 앱은 아직 스토어에 나가지 않았고(versionCode 1), 이 표에 줄을 쓰는 코드(연습 세션, 카드 만들기, 복습 화면)도 아직 없다.

## 후보

1. version 6 을 두고 5에서 6으로 옮기는 `ALTER TABLE ADD COLUMN` SQL 과 이전 테스트를 더한다
2. version 5 의 표 정의와 4에서 5로 옮기는 `CREATE TABLE` SQL 에 열을 바로 넣는다

## 결정

2를 고른다.

- 열은 version 5 의 Row 클래스와 `OPIC_MIGRATIONS` 의 4에서 5 SQL 에 함께 더한다. `Migration4To5Test` 가 새 열까지 쓰고 읽는다
- 이 방식은 version 5 가 담긴 빌드가 처음 출시되기 전까지만 쓴다. 출시 뒤의 표 변경은 새 version 과 이전 SQL 로 한다

## 이유

version 5 표를 가진 기기는 개발 기기뿐이고 그 표에는 줄이 없다. 지킬 사용자 데이터가 없는데 후보 1을 고르면 아무 사용자도 거치지 않을 5에서 6 이전 SQL 과 테스트가 영원히 남고, 모든 설치가 4에서 5, 5에서 6을 차례로 돈다. `exportSchema` 를 끈 채로 손으로 쓴 SQL 로 이전을 확인하는 지금 방식(OpicDatabase 의 ponytail 주석)에서는 이전 단계가 늘수록 SQL 과 Row 정의가 어긋날 곳도 는다.

후보 2는 4에서 5 SQL 하나만 고치면 되고, 기존 이전 테스트가 그대로 새 열을 확인한다.

## 결과

개발 기기에 이미 version 5 DB 가 있으면 Room 의 스키마 확인이 맞지 않아 앱이 열리지 않는다. 그 기기는 앱 데이터를 지우고 다시 설치한다. 표에 줄이 없으므로 잃는 것은 version 4 까지의 회차와 코치 기록뿐이다.

version 5 가 담긴 빌드를 내부 테스트나 스토어로 내보내면 이 결정은 끝난다. 그 뒤로는 version 을 올린다.

## 근거

- [DB 와 이전 SQL](../../engine/answer/src/commonMain/kotlin/io/github/nosorae/aiopic/engine/answer/store/OpicDatabase.kt)
- [4에서 5 이전 테스트](../../engine/answer/src/commonTest/kotlin/io/github/nosorae/aiopic/engine/answer/store/Migration4To5Test.kt)
- [표 스키마](../specs/design.md)
