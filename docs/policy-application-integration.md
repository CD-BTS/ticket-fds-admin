# 정책 적용 기록 연동 (#67)

BTS(ticket-fds-admin)는 정책을 **결정**만 합니다. 그 정책이 실제로 **적용**됐는지는 티켓팅 서비스만 압니다.
이 문서는 티켓팅 서비스(ticket-fds-demo-ticketing)가 ① 정책 요청에 공연 ID를 싣고 ② 정책을 실제로 적용한 시점에
BTS에 기록을 보내는 방법을 정리합니다. BTS 쪽 구현은 #67에 들어 있고, demo-ticketing 쪽은 별도 이슈에서 진행합니다.

연동 전까지 관리자 통계의 실제 제재 값은 "기록 없음"(`sanctions.recorded = false`, 나머지 null)으로 나옵니다. 정상입니다.

## 1. 정책 결정 요청 변경

`POST /api/v1/users/{userId}/policy-decisions` (서비스 키)

```json
{ "context": "QUEUE_JOIN", "concertId": "c1" }
```

- `concertId`는 **선택**입니다(최대 50자). 없으면 지금과 똑같이 동작하고 `policy_decision.concert_id`가 null로 남습니다.
- 응답에 `concertId`가 추가됐습니다. `decisionId`는 적용 기록을 보낼 때 필요하므로 보관해야 합니다.
- 응답의 `decidedAt`은 UTC(`...Z`)입니다. 이전에는 UTC 값을 변환 없이 `+09:00`으로 표기했습니다.

## 2. 정책 적용 기록 API

`POST /api/v1/policy-applications` (서비스 키 `X-API-Key`)

```json
{
  "serviceId": "demo-ticketing",
  "externalApplicationId": "outbox-18342",
  "userId": "<BTS 사용자 UUID>",
  "decisionId": 123,
  "concertId": "c1",
  "action": "REQUIRE_CAPTCHA",
  "result": "CHALLENGED",
  "appliedAt": "2026-11-24T13:07:12.123Z"
}
```

| 필드 | 규칙 |
|---|---|
| `serviceId` | 인증된 서비스와 같아야 합니다. 다르면 403 |
| `externalApplicationId` | 멱등 키. 서비스 안에서 고유, 최대 100자 |
| `userId` | 그 서비스의 사용자. 아니면 404 `USER_NOT_FOUND` |
| `decisionId` | 그 사용자에게 내려진 정책 결정. 아니면 404 `RESOURCE_NOT_FOUND` |
| `concertId` | 정책이 적용된 공연(필수). 결정에 공연 ID가 있으면 같아야 하고, 다르면 409 |
| `action` | 결정의 조치와 같아야 합니다. 다르면 409 |
| `result` | 아래 표의 조합만 허용. 아니면 400 |
| `appliedAt` | UTC 오프셋이 붙은 ISO-8601, 밀리초까지. 오프셋이 없으면 400 |

| action | 허용 result | 의미 |
|---|---|---|
| `REQUIRE_CAPTCHA` | `CHALLENGED` | CAPTCHA를 실제로 요구함 |
| | `PASSED` / `FAILED` | 요구한 CAPTCHA를 통과함 / 실패함 |
| `DELAY` | `DELAYED` | 후순위 대기열에 실제로 배치됨 |
| `RESTRICT` | `BLOCKED` | 대기열 입장 또는 좌석 선점이 실제로 거부됨 |

`ALLOW`는 제재가 아니므로 보내지 않습니다(보내면 400).

**응답**

| 상황 | 상태 | 비고 |
|---|---|---|
| 새 기록 | 201 `COMMON_201` | `data.applicationId` |
| 같은 키, 같은 내용 재전송 | 200 `COMMON_200` | 기존 기록을 그대로 돌려줌. 새 행 없음 |
| 같은 키, 다른 내용 | 409 `DATA_CONFLICT` | 기존 기록은 바뀌지 않음 |

**공연 ID가 없는 결정**: 결정에 `concert_id`가 없으면(연동 전 요청 등) 적용 기록의 `concertId`를 그대로 씁니다.
공연별 통계는 항상 적용 기록의 공연 ID로 집계합니다.

**AI · Credit과의 분리**: 적용 기록은 `policy_application`에만 저장하고 `behavior_event`에는 쓰지 않습니다.
AI 평가 대상 선정, Feature 6개, Credit 산출에 영향을 주지 않습니다. 그래서 Event API로 보내면 안 됩니다.

## 3. 정책별 기록 시점

정책 응답을 받았다는 것만으로는 기록하지 않습니다. **실제로 적용된 순간**에만 기록합니다.

| 조치 | 기록하는 시점 | result | 기록하지 않는 경우 |
|---|---|---|---|
| REQUIRE_CAPTCHA | 대기열 입장에서 CAPTCHA를 실제로 요구했을 때 | `CHALLENGED` | 응답만 받고 사용자가 이탈 |
| | CAPTCHA 검증이 끝났을 때 | `PASSED` / `FAILED` | |
| DELAY | 후순위 대기열(`waiting-delayed`)에 실제로 들어갔을 때 | `DELAYED` | CAPTCHA 단계에서 이탈해 대기열에 들어가지 못함 |
| RESTRICT | 대기열 입장이 `QUEUE_RESTRICTED`로 거부됐을 때 | `BLOCKED` | |
| | 좌석 선점이 `BOOKING_RESTRICTED`로 거부됐을 때 | `BLOCKED` | BTS 장애로 선점을 그대로 허용한 경우(fail-open) |

DELAY 결정은 CAPTCHA도 함께 요구합니다. 이 CAPTCHA는 DELAY 결정의 일부이므로 따로 `REQUIRE_CAPTCHA`로 보내지 않습니다
(결정의 조치와 다른 `action`은 409입니다). DELAY는 대기열 배치 시점의 `DELAYED` 한 건만 보냅니다.

## 4. 기존 Outbox 재사용

정책 적용은 사용자 요청 흐름 안에서 일어나므로, 요청 안에서 BTS를 직접 호출하지 말고 기존 Outbox에 적재합니다.

- `outbox_events`에 새 유형 `POLICY_APPLICATION`을 추가하고 기존 워커(`OutboxDeliveryService`, 5초 주기)가 보냅니다.
- 재시도 · 백오프(최대 8회, `min(300, 2^n)`초) · 임대(60초)는 그대로 씁니다.
- 전송 대상만 다릅니다. 행동 이벤트는 `POST /api/v1/events`, 정책 적용은 `POST /api/v1/policy-applications`입니다.
- 정책 적용 행은 예매 단위 순서 보장(같은 `aggregate_id`의 이전 행이 SENT일 때까지 대기)에서 제외합니다. `BOOKING_ATTEMPT`와 같은 취급입니다.
- 사용자의 BTS UUID가 아직 없으면(첫 호출 전) 기존 지연 등록 로직을 그대로 탑니다.
- 전송이 끝내 실패해도(FAILED) 대기열 · 예매 동작에는 영향이 없습니다. 통계에서만 빠집니다.

## 5. 멱등 키 생성

`externalApplicationId`는 **Outbox 행을 만들 때 한 번** 정하고 페이로드에 얼려 둡니다(재시도 때 다시 만들지 않습니다).

- 권장: `"pa-" + outbox_events.id` 또는 적재 시 만든 UUID.
- 같은 적용을 두 번 적재하지 않으려면 자연 키를 씁니다: `decisionId + ":" + result`
  (예: `123:CHALLENGED`). 한 결정에서 같은 result는 한 번만 기록됩니다.
- 같은 키로 내용이 다른 요청은 409이므로, 키는 반드시 내용과 1:1이어야 합니다.

## 6. 변경이 필요한 파일 (demo-ticketing)

| 파일 | 변경 |
|---|---|
| `integration/bts/BtsClient.java` | 정책 요청 본문에 `concertId` 추가, 응답의 `decisionId` 반환, `POST /api/v1/policy-applications` 호출 메서드 추가 |
| `integration/bts/PolicyDecisionClient` · `PolicyConfiguration.java` | 조치와 함께 `decisionId`를 돌려주도록 반환형 확장. `mock` · `disabled` 모드는 `decisionId` 없음(적용 기록을 만들지 않음) |
| `queue/QueueService.java`, `queue/QueueJoinPolicy.java`, `queue/QueueController.java` | 입장 판정에 쓴 `decisionId`를 대기열 상태에 보관. CAPTCHA 요구 · 검증 결과 · 후순위 배치 · 입장 거부 시점에 Outbox 적재 |
| `resources/queue.lua` | 후순위 배치 · 거부 여부를 호출자가 알 수 있도록 반환값 확인(이미 구분되면 변경 없음) |
| `booking/policy/BookingPolicyGuard.java` | RESTRICT로 선점을 거부한 시점에 Outbox 적재 |
| `outbox/OutboxRepository.java`, `outbox/OutboxDeliveryService.java` | `POLICY_APPLICATION` 유형 적재 · 전송, 순서 보장 예외 처리 |
| DB 스크립트 | `outbox_events.event_type` 허용 값에 `POLICY_APPLICATION` 추가(제약이 있다면) |
| `e2e/Start-E2E.ps1` 등 | BTS DB에 `db/manual/067-admin-query-storage.sql` 적용 |

정책 결정은 지금 CAPTCHA 발급 · 검증 때마다 다시 요청합니다. 적용 기록은 **그 적용을 만든 결정**의 `decisionId`를 써야 하므로,
입장 판정 때 받은 `decisionId`를 CAPTCHA 흐름 끝까지 들고 가는 것이 핵심입니다.

## 7. 연동 테스트 시나리오

1. **공연 ID 전달**: 대기열 입장 후 BTS `policy_decision.concert_id`가 그 공연 ID인지 확인.
2. **CAPTCHA 요구 · 통과**: REQUIRE_CAPTCHA 사용자가 입장 → `CHALLENGED` 1건, 검증 성공 → `PASSED` 1건.
   관리자 공연 상세에서 `sanctions.byPolicy.REQUIRE_CAPTCHA.applicationCount`가 1(2가 아님), `captcha.passed`가 1.
3. **CAPTCHA 실패**: 잘못된 토큰 → `FAILED` 1건. 사용자는 대기열에 들어가지 못함.
4. **응답만 받고 이탈**: 정책 응답 후 아무 요청도 하지 않음 → 적용 기록 0건.
5. **DELAY**: 후순위 대기열 배치 → `DELAYED` 1건. `REQUIRE_CAPTCHA` 기록은 생기지 않음.
6. **RESTRICT**: 대기열 거부 → `BLOCKED` 1건. 좌석 선점 거부 → `BLOCKED` 1건.
7. **BTS 중지 중 적용**: Outbox에 RETRY로 남고, BTS 재기동 후 전송되어 **한 번만** 저장됨.
8. **재전송**: 같은 Outbox 행을 다시 보내면 200이고 BTS 행 수는 그대로.
9. **AI 평가 불변**: 적용 기록만 보낸 사용자는 `behavior_event`가 늘지 않고 재평가 대상이 되지 않음.
10. **기록 없음 구분**: 연동하지 않은 서비스의 관리자 요약은 `sanctions.recorded = false`.
