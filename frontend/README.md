# BTS Admin Dashboard (Frontend)

최신 `ticket-fds-backend`(dev, #38 공연·관리자 조회 API 포함)의 관리자 API에 연결되는 React + TypeScript + Vite 관리자 대시보드입니다.

## 실행 방법

```bash
npm install
cp .env.example .env      # Windows PowerShell: Copy-Item .env.example .env
npm run dev
```

기본 주소는 `http://localhost:5173`이며 `/api` 요청은 `http://localhost:8080`으로 프록시됩니다(백엔드에 CORS 설정이 없음). 주소가 다르면 `.env`의 `VITE_API_PROXY_TARGET`을 수정하고 Vite를 재시작합니다.

## 인증

**로그인** 화면에서 **서비스 ID**와 **관리자 API Key** 하나만 입력합니다. 공연·사용자 메뉴는 로그인 후에만 열립니다. 관리자 키는 백엔드 `BTS_ADMIN_API_KEYS`에 해시로 등록한 키이며, 모든 요청에 `X-API-Key`로 전달됩니다. 키는 저장소(localStorage/sessionStorage)에 저장하지 않고 메모리에만 두므로 새로고침하면 다시 입력합니다. 서비스 키로는 관리자 API를 호출할 수 없습니다(403).

## 연동 API (모두 관리자 키)

| API | 화면 |
|---|---|
| `GET /api/v1/admin/summary?serviceId=` | 연결 확인, 등급 목록, 상단 요약 |
| `GET /api/v1/admin/concerts?serviceId=` | 공연 목록 (정렬·페이지네이션, 공연별 이상탐지 사용자 수) |
| `GET /api/v1/admin/concerts/{concertId}?serviceId=` | 공연 상세 (예매 시도·성공·실패·미종료 건수, 이상탐지 인원, 예상 제재 대상, 실제 제재 적용 인원) |
| `GET /api/v1/admin/concerts/{concertId}/trends?serviceId=` | 공연 상세의 시간대별 추이 그래프 (60초 이상 간격, 조회 범위 선택) |
| `GET /api/v1/admin/concerts/{concertId}/users?serviceId=` | 공연별 사용자 (등급 필터, Credit 정렬) |
| `GET /api/v1/admin/users` | 전체 사용자 목록 (ID·등급·상태·Credit 범위 필터, Credit 정렬) |
| `GET /api/v1/admin/users/{userId}` | 사용자 상세 + 최근 정책 결정, 평가 상태, 예상 정책, 최신 Credit 산출 근거 |
| `GET /api/v1/admin/users/{userId}/credit-history` | 사용자 상세의 Credit 변경 이력과 산출 근거 |
| `GET /api/v1/admin/users/{userId}/assessments` | 성공한 평가 이력과 평가 당시 Feature |
| `GET /api/v1/admin/users/{userId}/assessment-attempts` | AI 평가 시도 이력 (데이터 부족 · 평가 불가 포함) |
| `GET /api/v1/admin/users/{userId}/events` | 사용자 상세의 예매 목록 (`BOOKING_ATTEMPT`를 `attemptId`로 묶어 성공/실패·최종 단계 계산, 최근 300건까지) |
| `GET /api/v1/admin/users/{userId}/features` | 사용자 상세의 Feature별 계산값 (AI 입력 6개) |

공연 데이터는 외부 서비스가 `PUT /api/v1/concerts/{concertId}`로 등록해야 보입니다. 기존 DB라면 `backend/db/manual/038-create-concert.sql`을 한 번 적용해야 합니다.

## 화면 구성

상단 메뉴(공연 목록 · 사용자 목록 · 소개 · 로그인)로 이동합니다. 로고는 확정 전이라 `App.tsx`와 `pages/Intro.tsx`의 자리표시를 교체하면 됩니다.

```
소개 → 로그인 → 공연 목록 → 공연 상세 → 예매자 목록 → 사용자 조회 → (예매 목록 → 공연 상세)
                사용자 목록 → 사용자 조회
```

## 값이 없을 때의 표시

임의의 값을 만들어 보여주지 않습니다. 0과 "없음"을 구분합니다.

| 상황 | 표시 |
|---|---|
| 외부 서비스가 정책 적용 기록을 보낸 적이 없음 (`sanctions.recorded = false`) | 제재 적용 "기록 없음" (0명이 아님) |
| 산출 근거를 저장하기 전에 계산된 Credit (`latestCalculation`, 이력의 `calculation`이 null) | "기록 없음" / "산출 근거 기록 없음" |
| 평가 당시 Feature를 저장하기 전의 평가 (`features`가 null) | "기록 없음" |
| AI에 null로 보낸 Feature 값 | "계산 불가" |
| 성공한 AI 평가가 없는 사용자 (`anomalyScore`가 null) | 이상 점수 "—", 평가 상태 "미평가" |
| 선택한 구간에 추이 데이터가 없음 | 그래프 대신 안내 문구 |

화면의 등급은 Credit 등급(`tier`)입니다. AI 이상탐지(이상 점수 0.5 이상), 예상 정책, 실제 정책 결정, 실제 제재 적용은 각각 따로 표시합니다.

## 아직 화면에 연결하지 않은 백엔드 기능

- 사용자 목록의 이상 점수 정렬 · 범위 필터, 평가 여부 필터, ID 부분 검색 (`sort=anomalyScore`, `minAnomalyScore`, `assessed`, `keyword`)
- 공연 목록의 서버 검색 (`name`)
- 요약의 평가 완료 · 미평가 · 이상탐지 인원, 예상 정책별 인원
- 공연 상세의 실패 유형별 건수, 예매 · 취소 · 입장 건수, 평균 Credit

## 참고

- 사용자 목록의 생성 · 평가 시각(`LocalDateTime`)은 서버가 타임존 없이 내려주므로 그대로 표시합니다. 공연 · 이벤트 · 정책 · Credit 이력 시각과 사용자 상세의 평가 시각은 UTC로 해석해 브라우저 시간대로 표시합니다.
- 공연 목록의 검색 입력은 불러온 목록 안에서만 걸러냅니다(서버 검색 `name`은 아직 연결하지 않음).
- 이상 점수는 공연별 평가가 아니라 사용자 전체의 최신 평가입니다.
