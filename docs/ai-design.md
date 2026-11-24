# AI 이상도 평가 연동 설계 (#40)

BTS(admin)가 행동 이벤트로 Feature 6개를 집계해 ticket-fds-ai에 보내고, 받은 이상 점수를 저장한 뒤 Credit과 정책에 반영하는 흐름입니다.

```
Demo ──(행동 이벤트)──▶ behavior_event
                              │  새 이벤트(event_id 증가)가 들어온 사용자
                              ▼
          RiskAssessmentScheduler (bts.ai.poll-ms마다, bts.ai.enabled=true일 때만)
                              │  FeatureAggregationService: 최근 30일 Feature 6개
                              ▼
          ticket-fds-ai POST /predict/batch (최대 bts.ai.batch-size건씩)
                              │
          ┌───────────────────┼─────────────────────────┐
          ▼ OK                ▼ INSUFFICIENT_DATA        ▼ INVALID
   risk_assessment 저장      저장 안 함                  저장 안 함 + 경고 로그
   RiskAssessedEvent 발행
          │                   (세 경우 모두 risk_assessment_cursor에 진행 위치 기록)
          ▼ 커밋 후 (AFTER_COMMIT, 새 트랜잭션)
   CreditEvaluationService → user_credit 갱신 + credit_history("AI 이상도 평가 반영")
          │
          ▼
   PolicyDecisionService: 다음 QUEUE_JOIN / BOOKING_ATTEMPT 판정에 최신 이상 점수 사용
```

## AI 계약 (ticket-fds-ai `app/schemas.py`, `docs/predict_feature_spec.md`)
| Feature | admin 계산 (`FeatureAggregationService`) |
|---|---|
| `bookingCount` | 최근 30일 고유 예매 시도 수 (BOOKING_ATTEMPT의 `attemptId`). 0이면 AI가 `INSUFFICIENT_DATA` |
| `avgBookingInterval` | 시도 시작 시각 사이 간격의 평균(초, 밀리초 정밀도). 시도 2건 미만이면 null |
| `bookingIntervalVariance` | 같은 간격의 모분산(초²). **간격 5개(시도 6건) 미만이면 null** (ticket-fds-ai #20) |
| `cancelRate` | 예매 완료(BOOKING) 대비 취소 비율. 완료 0건이면 null |
| `cardReuseCount` | 같은 서비스·30일 안에 같은 결제수단으로 예매한 고유 계정 수의 최댓값 (본인 포함, 최소 1) |
| `ipBookingDensity` | 같은 서비스·30일 안에 같은 IP로 예매한 고유 계정 수의 최댓값 (본인 포함, 최소 1) |

- 계산 불가 값은 0으로 바꾸지 않고 null로 보냅니다. AI가 학습 데이터 중앙값으로 대체합니다.
- `calculatedAt`은 한 주기 안에서 같은 UTC 시각(`...Z`)을 씁니다. `windowDays`는 30입니다.
- 응답의 `anomalyScore`는 0~1로 정규화된 점수이고, **0.5가 탐지 임계값**입니다(정상 사용자 오탐률 5% 기준). 매크로일 확률은 아닙니다.
- 정책·Credit 임계값은 #60에서 이 점수 체계에 맞춰 조정했습니다(`PolicyDecisionService`, `CreditCalculator`).

## 평가 대상과 재시도 (`risk_assessment_cursor`)
- **평가 대상:** 마지막 평가 이후 `behavior_event.event_id`가 늘어난 사용자. 늦게 도착한 이벤트(Outbox 재전송)도 event_id로 잡힙니다.
- **진행 위치 기록:** OK뿐 아니라 INSUFFICIENT_DATA·INVALID도 기록합니다. 데이터가 부족한 사용자를 매 주기 반복 호출하지 않기 위해서입니다. 새 이벤트가 오면 다시 평가합니다.
- **AI 서버 장애:** 네트워크 오류, 타임아웃, 4xx/5xx가 나면 진행 위치를 바꾸지 않습니다. 다음 주기에 그대로 다시 시도합니다.
- **처리 단위:** 사용자별로 트랜잭션을 따로 엽니다. 한 사용자의 저장 실패가 다른 사용자에게 영향을 주지 않습니다.
- **응답 검증:** 응답의 userId가 요청과 다르거나, 점수가 0~1을 벗어나거나, `modelVersion`이 비었거나 30자를 넘으면 INVALID로 처리하고 저장하지 않습니다.

## Credit 반영 (`RiskAssessedCreditListener`)
- `@TransactionalEventListener(AFTER_COMMIT)`와 REQUIRES_NEW 트랜잭션을 씁니다. 평가가 커밋된 뒤에만 Credit을 바꿉니다.
- Credit 재산출이 실패해도 평가는 되돌리지 않습니다. 다음 평가 때 다시 반영됩니다.
- 룰 감점의 `intervalVariance`에는 평가에 쓴 `bookingIntervalVariance`를 넘깁니다. `recentIpAttemptCount`(최근 10분 IP 시도)는 이 경로에서 알 수 없어 null(감점 없음)입니다.

## 설정 (`application.properties`)
| 환경변수 | 기본값 | 설명 |
|---|---|---|
| `AI_ENABLED` | `false` | true일 때만 스케줄러를 등록합니다. AI 서버 없이도 BTS가 기동합니다 |
| `AI_BASE_URL` | `http://localhost:8000` | ticket-fds-ai 주소 |
| `AI_POLL_MS` | `60000` | 평가 주기(ms) |
| `AI_BATCH_SIZE` | `100` | 한 번의 `/predict/batch` 요청 건수 (1~1000) |
| `AI_MAX_USERS_PER_RUN` | `500` | 한 주기에 평가할 최대 사용자 수 |
| `AI_CONNECT_TIMEOUT` / `AI_READ_TIMEOUT` | `PT2S` / `PT10S` | AI 호출 타임아웃 |

**기존 DB:** `db/manual/062-create-risk-assessment-cursor.sql`을 먼저 적용합니다.

## 실서버 검증 (2026-11-24)
Demo, BTS, ticket-fds-ai(`fds-if-v4.0.0`, 로컬 uvicorn)를 함께 띄우고 `AI_POLL_MS=5000`으로 확인했습니다.

| 사용자 | AI에 보낸 Feature | 이상 점수 | Credit | 다음 대기열 입장 |
|---|---|---|---|---|
| 정상 (시도 1번 → 예매) | bookingCount 1, 간격·분산 null | 0.009 | 50 (NORMAL) | — |
| 매크로 (0.28초 간격 시도 12번) | bookingCount 12, 평균 0.28초, 분산 0.0002 | 0.68 | 31 (SUSPICIOUS) | `REQUIRE_CAPTCHA` (판정에 이상 점수 0.68 사용) |

기존 e2e 시나리오(ticket-fds-demo-ticketing `e2e/`)도 AI 연동을 켠 상태로 69/70 통과했습니다. 실패 1건은 e2e 스크립트가 옛 대시보드 경로(`/dashboard/summary`)를 부른 것으로, 이 변경과 관계없습니다.

## 알려진 한계
- **평가 이력:** 이벤트가 들어올 때마다 다시 평가해서, 연속으로 시도하는 사용자는 짧은 간격으로 평가 이력이 여러 건 쌓입니다. Credit이 바뀌지 않아도 `credit_history`가 남는 것은 기존 `CreditEvaluationService` 동작입니다.
- **동시성:** `UserCredit`에 낙관적 잠금이 없습니다. 수동 평가 API와 자동 갱신이 동시에 돌면 마지막 쓰기가 남습니다.
- **감점 중복:** 같은 신호가 두 번 감점됩니다. 간격 분산은 AI Feature와 룰 감점에 모두 반영되고, 카드 공유도 AI Feature(`cardReuseCount`)와 룰(`user_card`)에 모두 반영됩니다.
