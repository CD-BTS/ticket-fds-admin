CREATE TABLE `user_account` (
  `user_id` varchar(50) PRIMARY KEY COMMENT 'BTS 내부 사용자 ID',
  `service_id` varchar(50) NOT NULL COMMENT '외부 티켓팅 서비스 식별자',
  `external_user_id` varchar(100) NOT NULL COMMENT '외부 서비스의 사용자 ID',
  `created_at` datetime NOT NULL DEFAULT (now()),
  `account_status` varchar(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, SUSPENDED'
);

CREATE TABLE `behavior_event` (
  `event_id` bigint PRIMARY KEY AUTO_INCREMENT,
  `user_id` varchar(50) NOT NULL,
  `service_id` varchar(50) NOT NULL COMMENT '이벤트가 발생한 외부 서비스',
  `external_event_id` varchar(100) NOT NULL COMMENT '외부 서비스의 이벤트 고유 ID',
  `event_type` varchar(30) NOT NULL COMMENT 'BOOKING, CANCEL, CHECK_IN',
  `booking_id` varchar(100) COMMENT '외부 예매 ID. BOOKING/CANCEL 시 필수',
  `ticket_id` varchar(100) COMMENT '외부 티켓 ID. CHECK_IN 시 필수',
  `occurred_at` datetime(3) NOT NULL COMMENT '외부 서비스에서 실제 행동이 발생한 시각 (UTC, 밀리초)',
  `metadata` json COMMENT '추가 행동 정보. bookingDurationSeconds, deviceChanged 등 확장 가능'
);

CREATE TABLE `user_credit` (
  `user_id` varchar(50) PRIMARY KEY,
  `credit_score` int NOT NULL DEFAULT 50 COMMENT '관람 신용 점수 0~100, 초기값 50',
  `dynamic_penalty` double COMMENT '마지막 평가에서 적용한 DynamicPenalty(AI+Rule, 0~70). 미저장이면 NULL',
  `tier` varchar(20) NOT NULL DEFAULT 'BASE' COMMENT '관람 신용 등급. 구체적인 등급 기준은 추후 확정',
  `updated_at` datetime NOT NULL DEFAULT (now()) COMMENT '신용 점수 및 등급 최종 갱신 시각',
  `latest_calculation_id` bigint COMMENT '현재 점수를 만든 최신 산출 근거(credit_calculation). #67 이전 행은 NULL'
);

CREATE TABLE `credit_history` (
  `history_id` bigint PRIMARY KEY AUTO_INCREMENT,
  `user_id` varchar(50) NOT NULL,
  `event_id` bigint COMMENT '관련 BTS 내부 행동 이벤트 ID. 직접 관련 이벤트가 없으면 NULL',
  `score_before` int NOT NULL COMMENT '변경 전 신용 점수',
  `score_after` int NOT NULL COMMENT '변경 후 신용 점수',
  `reason` varchar(255) NOT NULL COMMENT '신용 점수 변경 사유',
  `created_at` datetime NOT NULL DEFAULT (now()) COMMENT '신용 점수 변경 시각'
);

CREATE TABLE `risk_assessment` (
  `assessment_id` bigint PRIMARY KEY AUTO_INCREMENT,
  `user_id` varchar(50) NOT NULL,
  `anomaly_score` double NOT NULL COMMENT '정규화된 이상도 점수 0~1. 높을수록 이상 행동 정도가 큼',
  `model_version` varchar(30) NOT NULL COMMENT '분석에 사용한 AI 모델 버전',
  `assessed_at` datetime NOT NULL DEFAULT (now()) COMMENT 'AI 분석 수행 시각',
  `booking_count` int COMMENT 'AI에 보낸 Feature: 최근 30일 고유 예매 시도 수 (#67 이전 행은 NULL)',
  `avg_booking_interval` double COMMENT 'AI에 보낸 Feature: 시도 간격 평균(초). 계산 불가면 NULL (#67 이전 행은 NULL)',
  `booking_interval_variance` double COMMENT 'AI에 보낸 Feature: 시도 간격 모분산(초^2). 간격 5개 미만이면 NULL (#67 이전 행은 NULL)',
  `cancel_rate` double COMMENT 'AI에 보낸 Feature: 취소율 0~1. 예매 0건이면 NULL (#67 이전 행은 NULL)',
  `card_reuse_count` int COMMENT 'AI에 보낸 Feature: 같은 결제수단 고유 계정 수 (#67 이전 행은 NULL)',
  `ip_booking_density` int COMMENT 'AI에 보낸 Feature: 같은 IP 고유 계정 수 (#67 이전 행은 NULL)'
);

CREATE TABLE `policy_decision` (
  `decision_id` bigint PRIMARY KEY AUTO_INCREMENT,
  `user_id` varchar(50) NOT NULL,
  `assessment_id` bigint COMMENT '정책 결정에 사용한 AI 분석 ID. 분석 이력이 없으면 NULL',
  `context` varchar(30) NOT NULL COMMENT '정책 요청 상황. MVP: BOOKING_ATTEMPT',
  `credit_score_at_decision` int NOT NULL COMMENT '정책 결정 당시 관람 신용 점수',
  `anomaly_score_at_decision` double COMMENT '정책 결정 당시 사용한 이상도 점수. 분석 이력이 없으면 NULL',
  `action` varchar(30) NOT NULL COMMENT 'ALLOW, REQUIRE_CAPTCHA, DELAY, RESTRICT',
  `reason` varchar(255) COMMENT '정책 결정 사유',
  `decided_at` datetime NOT NULL DEFAULT (now()) COMMENT '정책 결정 시각',
  `service_id` varchar(50) COMMENT '정책을 요청한 외부 서비스. #67 이전 행은 NULL',
  `concert_id` varchar(50) COMMENT '정책을 요청한 공연. 요청에 없으면 NULL'
);

-- 7. 예매 및 관람 이력 테이블. 티켓 1장당 1행(#56). 기존 DB에는 db/manual/056-booking-history-attendance.sql만 적용한다.
CREATE TABLE IF NOT EXISTS booking_history (
    booking_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(50) NOT NULL,
    service_id VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '외부 티켓팅 서비스 식별자. #56 이전 행은 NULL',
    ticket_id VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '외부 티켓 ID. 관람 이력은 티켓 단위. #56 이전 행은 NULL',
    booking_reference VARCHAR(100) NOT NULL COMMENT '외부 예매 고유 번호 (behavior_event.booking_id와 같은 길이)',
    event_id VARCHAR(50) NULL COMMENT '공연/경기 ID',
    status VARCHAR(20) NOT NULL COMMENT 'BOOKED, COMPLETED, NO_SHOW, AUTO_CANCELLED, CANCELLED',
    booked_at DATETIME NOT NULL COMMENT '예매 시점',
    event_at DATETIME NOT NULL COMMENT '공연/경기 관람 예정 시점',
    event_ends_at DATETIME NULL COMMENT '공연 종료 시각 (UTC, 예매 당시 값). NO_SHOW 판정 기준',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_booking_user FOREIGN KEY (user_id) REFERENCES user_account (user_id) ON DELETE CASCADE,
    UNIQUE KEY uk_booking_service_ticket (service_id, ticket_id),
    INDEX idx_booking_user_status (user_id, status),
    INDEX idx_booking_user_event (user_id, event_at),
    INDEX idx_booking_status_ends (status, event_ends_at)
) ENGINE=InnoDB; -- 문자셋·collation은 DB 기본값을 따른다. 명시하면 user_account FK와 어긋날 수 있다(#46)

-- 8. 결제 카드 식별 정보 테이블 (다계정 카드 공유 탐지용)
CREATE TABLE IF NOT EXISTS user_card (
    card_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(50) NOT NULL,
    card_hash VARCHAR(64) NOT NULL COMMENT '카드 번호 SHA-256 해시값',
    card_company VARCHAR(30) NULL COMMENT '카드사명 (예: 현대, 신한)',
    masked_card_no VARCHAR(20) NULL COMMENT '마스킹 번호 (예: 1234-56**-****-1234)',
    last_used_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_card_user FOREIGN KEY (user_id) REFERENCES user_account (user_id) ON DELETE CASCADE,
    INDEX idx_user_card_hash (card_hash),
    INDEX idx_user_card_user (user_id)
) ENGINE=InnoDB; -- 문자셋·collation은 DB 기본값을 따른다. 명시하면 user_account FK와 어긋날 수 있다(#46)

CREATE UNIQUE INDEX `user_account_index_0` ON `user_account` (`service_id`, `external_user_id`);

CREATE INDEX `user_account_index_1` ON `user_account` (`service_id`);

CREATE INDEX `user_account_index_2` ON `user_account` (`account_status`);

CREATE UNIQUE INDEX `behavior_event_index_3` ON `behavior_event` (`service_id`, `external_event_id`);

CREATE INDEX `behavior_event_index_4` ON `behavior_event` (`user_id`);

CREATE INDEX `behavior_event_index_5` ON `behavior_event` (`user_id`, `occurred_at`);

CREATE INDEX `behavior_event_index_6` ON `behavior_event` (`user_id`, `event_type`, `occurred_at`);

CREATE INDEX `behavior_event_index_7` ON `behavior_event` (`service_id`, `booking_id`);

CREATE INDEX `user_credit_index_8` ON `user_credit` (`tier`);

CREATE INDEX `credit_history_index_9` ON `credit_history` (`user_id`, `created_at`);

CREATE INDEX `credit_history_index_10` ON `credit_history` (`event_id`);

CREATE INDEX `risk_assessment_index_11` ON `risk_assessment` (`user_id`, `assessed_at`);

CREATE INDEX `risk_assessment_index_12` ON `risk_assessment` (`assessed_at`);

CREATE INDEX `policy_decision_index_13` ON `policy_decision` (`user_id`, `decided_at`);

CREATE INDEX `policy_decision_index_14` ON `policy_decision` (`user_id`, `action`, `decided_at`);

CREATE INDEX `policy_decision_index_15` ON `policy_decision` (`user_id`, `context`, `decided_at`);

CREATE INDEX `policy_decision_index_16` ON `policy_decision` (`assessment_id`);

CREATE INDEX `policy_decision_index_17` ON `policy_decision` (`decided_at`, `action`);

CREATE INDEX `policy_decision_index_18` ON `policy_decision` (`service_id`, `concert_id`, `decided_at`);

ALTER TABLE `user_account` COMMENT = '외부 티켓팅 서비스별 사용자 식별 정보. 사용자 등록 시 BTS 내부 user_id 생성';

ALTER TABLE `behavior_event` COMMENT = '외부 서비스에서 수집한 예매, 취소, 체크인 이벤트. 동일 외부 이벤트 중복 저장 방지';

ALTER TABLE `user_credit` COMMENT = '사용자별 현재 관람 신용 상태. 사용자 등록 시 함께 생성';

ALTER TABLE `credit_history` COMMENT = '관람 신용 점수 변경 이력. 점수 변경 시 기록';

ALTER TABLE `risk_assessment` COMMENT = 'Isolation Forest 기반 사용자 행동 분석 결과. 분석 시점 이전 30일의 행동 이벤트로 Feature 계산';

ALTER TABLE `policy_decision` COMMENT = '신용 점수와 최신 AI 이상도 점수를 바탕으로 결정한 대응 정책 및 당시 판단 근거';

ALTER TABLE `user_credit` ADD FOREIGN KEY (`user_id`) REFERENCES `user_account` (`user_id`);

ALTER TABLE `behavior_event` ADD FOREIGN KEY (`user_id`) REFERENCES `user_account` (`user_id`);

ALTER TABLE `credit_history` ADD FOREIGN KEY (`user_id`) REFERENCES `user_account` (`user_id`);

ALTER TABLE `credit_history` ADD FOREIGN KEY (`event_id`) REFERENCES `behavior_event` (`event_id`);

ALTER TABLE `risk_assessment` ADD FOREIGN KEY (`user_id`) REFERENCES `user_account` (`user_id`);

ALTER TABLE `policy_decision` ADD FOREIGN KEY (`user_id`) REFERENCES `user_account` (`user_id`);

ALTER TABLE `policy_decision` ADD FOREIGN KEY (`assessment_id`) REFERENCES `risk_assessment` (`assessment_id`);

-- #38: external-service concert metadata. Existing databases: apply db/manual/038-create-concert.sql only.
CREATE TABLE `concert` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `service_id` varchar(50) COLLATE utf8mb4_bin NOT NULL,
  `concert_id` varchar(50) COLLATE utf8mb4_bin NOT NULL,
  `name` varchar(200) NOT NULL,
  `starts_at` datetime NOT NULL COMMENT 'UTC',
  `ends_at` datetime NOT NULL COMMENT 'UTC',
  `venue` varchar(255) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE UNIQUE INDEX `concert_service_concert` ON `concert` (`service_id`, `concert_id`);

-- #40: 사용자별 AI 평가 진행 위치. 기존 DB: db/manual/062-create-risk-assessment-cursor.sql
CREATE TABLE `risk_assessment_cursor` (
  `user_id` varchar(50) PRIMARY KEY COMMENT 'BTS 내부 사용자 ID',
  `last_event_id` bigint NOT NULL COMMENT 'AI 평가에 마지막으로 반영한 behavior_event.event_id',
  `last_status` varchar(20) NOT NULL COMMENT 'OK, INSUFFICIENT_DATA, INVALID',
  `checked_at` datetime NOT NULL COMMENT '마지막 평가 시각 (UTC)'
);

-- #67: AI 평가 시도 이력. 성공한 평가는 risk_assessment에 있고, 여기에는 시도마다 상태를 남긴다.
-- AI 서버 장애·타임아웃은 기록하지 않는다(다음 주기에 재시도). 기존 DB: db/manual/067-admin-query-storage.sql
CREATE TABLE IF NOT EXISTS risk_assessment_attempt (
    attempt_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL COMMENT 'OK, INSUFFICIENT_DATA, INVALID',
    assessment_id BIGINT NULL COMMENT 'OK일 때 저장된 risk_assessment. Feature와 점수는 그 행에 있다',
    reason VARCHAR(500) NULL COMMENT '평가 불가 사유. OK면 NULL',
    model_version VARCHAR(30) NULL COMMENT 'AI 응답에 있던 모델 버전. 없으면 NULL',
    booking_count INT NULL COMMENT 'AI에 보낸 Feature. OK가 아닐 때만 저장(OK는 risk_assessment에 저장)',
    avg_booking_interval DOUBLE NULL,
    booking_interval_variance DOUBLE NULL,
    cancel_rate DOUBLE NULL,
    card_reuse_count INT NULL,
    ip_booking_density INT NULL,
    calculated_at DATETIME NOT NULL COMMENT '분석 시각 (UTC). AI 요청의 calculatedAt',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_attempt_user FOREIGN KEY (user_id) REFERENCES user_account (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_attempt_assessment FOREIGN KEY (assessment_id) REFERENCES risk_assessment (assessment_id) ON DELETE SET NULL,
    INDEX idx_attempt_user (user_id, attempt_id),
    INDEX idx_attempt_assessment (assessment_id)
) ENGINE=InnoDB; -- 문자셋·collation은 DB 기본값을 따른다

-- #67: Credit 산출 근거. Credit을 계산할 때마다 1행(점수가 그대로여도 기록).
-- kind=EVALUATION은 감점을 새로 계산한 평가, ATTENDANCE는 저장된 동적 감점을 재사용한 관람 이력 반영이다.
CREATE TABLE IF NOT EXISTS credit_calculation (
    calculation_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(50) NOT NULL,
    history_id BIGINT NULL COMMENT '같은 트랜잭션에서 남긴 credit_history',
    kind VARCHAR(20) NOT NULL COMMENT 'EVALUATION, ATTENDANCE',
    assessment_id BIGINT NULL COMMENT '산출에 쓴 AI 평가. 평가 이력이 없으면 NULL',
    source_calculation_id BIGINT NULL COMMENT 'ATTENDANCE: 재사용한 동적 감점을 계산한 EVALUATION 행',
    completed_count INT NOT NULL COMMENT '관람 완료 수 A',
    no_show_count INT NOT NULL COMMENT '노쇼 수 N',
    auto_cancelled_count INT NOT NULL COMMENT '자동 취소 수 C',
    anomaly_score DOUBLE NULL COMMENT '산출에 쓴 이상 점수. 없으면 NULL',
    recent_ip_attempt_count INT NULL COMMENT '룰 입력: 최근 10분 동일 IP 시도 수',
    interval_variance DOUBLE NULL COMMENT '룰 입력: 요청 간격 분산',
    card_shared_accounts INT NULL COMMENT '룰 입력: 같은 카드 고유 계정 수의 최댓값',
    base_credit DOUBLE NOT NULL COMMENT 'BaseCredit (반올림 전)',
    ai_penalty DOUBLE NULL COMMENT 'AI 감점. ATTENDANCE는 NULL',
    card_penalty DOUBLE NULL COMMENT '카드 공유 감점. ATTENDANCE는 NULL',
    ip_penalty DOUBLE NULL COMMENT 'IP 집중도 감점. ATTENDANCE는 NULL',
    variance_penalty DOUBLE NULL COMMENT '간격 분산 감점. ATTENDANCE는 NULL',
    rule_penalty_raw DOUBLE NULL COMMENT '룰 감점 합(상한 적용 전)',
    rule_penalty DOUBLE NULL COMMENT '룰 감점(상한 30 적용 후)',
    dynamic_penalty DOUBLE NOT NULL COMMENT '전체 동적 감점(상한 70 적용 후). ATTENDANCE는 재사용한 값',
    raw_credit DOUBLE NOT NULL COMMENT 'BaseCredit - 동적 감점 (0~100 제한과 반올림 전)',
    score_before INT NOT NULL,
    score_after INT NOT NULL,
    tier VARCHAR(20) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_calc_user FOREIGN KEY (user_id) REFERENCES user_account (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_calc_history FOREIGN KEY (history_id) REFERENCES credit_history (history_id) ON DELETE CASCADE,
    CONSTRAINT fk_calc_assessment FOREIGN KEY (assessment_id) REFERENCES risk_assessment (assessment_id) ON DELETE SET NULL,
    CONSTRAINT fk_calc_source FOREIGN KEY (source_calculation_id) REFERENCES credit_calculation (calculation_id) ON DELETE SET NULL,
    INDEX idx_calc_user (user_id, calculation_id),
    INDEX idx_calc_history (history_id)
) ENGINE=InnoDB; -- 문자셋·collation은 DB 기본값을 따른다

ALTER TABLE user_credit ADD CONSTRAINT fk_credit_latest_calculation
    FOREIGN KEY (latest_calculation_id) REFERENCES credit_calculation (calculation_id) ON DELETE SET NULL;

-- #67: 외부 티켓팅 서비스가 실제로 적용한 정책 기록. 정책을 "결정"한 policy_decision과 구분한다.
-- behavior_event와 분리되어 있어 AI 평가 대상 선정, Feature, Credit 산출에 쓰이지 않는다.
CREATE TABLE IF NOT EXISTS policy_application (
    application_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    service_id VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    external_application_id VARCHAR(100) NOT NULL COMMENT '외부 서비스의 멱등 키',
    user_id VARCHAR(50) NOT NULL,
    decision_id BIGINT NOT NULL COMMENT '적용한 정책 결정',
    concert_id VARCHAR(50) NOT NULL COMMENT '정책이 적용된 공연',
    action VARCHAR(30) NOT NULL COMMENT 'REQUIRE_CAPTCHA, DELAY, RESTRICT',
    result VARCHAR(20) NOT NULL COMMENT 'CHALLENGED, PASSED, FAILED, DELAYED, BLOCKED',
    applied_at DATETIME(3) NOT NULL COMMENT '외부 서비스에서 정책이 적용된 시각 (UTC)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_application_user FOREIGN KEY (user_id) REFERENCES user_account (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_application_decision FOREIGN KEY (decision_id) REFERENCES policy_decision (decision_id) ON DELETE CASCADE,
    UNIQUE KEY uk_application_service_external (service_id, external_application_id),
    INDEX idx_application_concert (service_id, concert_id, applied_at),
    INDEX idx_application_user (user_id, applied_at),
    INDEX idx_application_decision (decision_id)
) ENGINE=InnoDB; -- 문자셋·collation은 DB 기본값을 따른다
