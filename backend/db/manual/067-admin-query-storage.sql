-- Apply once to an existing BTS database (#67). Adds storage for admin queries; no existing row is changed or backfilled.
-- New columns are NULL for rows written before #67. Do not rerun the complete schema.sql on an existing DB.
-- Requires: 056 (booking_history), 062 (risk_assessment_cursor). Run the statements in this order.

-- 1. AI 평가 당시 Feature (성공한 평가)
ALTER TABLE risk_assessment
    ADD COLUMN booking_count INT NULL COMMENT 'AI에 보낸 Feature: 최근 30일 고유 예매 시도 수 (#67 이전 행은 NULL)',
    ADD COLUMN avg_booking_interval DOUBLE NULL COMMENT 'AI에 보낸 Feature: 시도 간격 평균(초). 계산 불가면 NULL (#67 이전 행은 NULL)',
    ADD COLUMN booking_interval_variance DOUBLE NULL COMMENT 'AI에 보낸 Feature: 시도 간격 모분산(초^2). 간격 5개 미만이면 NULL (#67 이전 행은 NULL)',
    ADD COLUMN cancel_rate DOUBLE NULL COMMENT 'AI에 보낸 Feature: 취소율 0~1. 예매 0건이면 NULL (#67 이전 행은 NULL)',
    ADD COLUMN card_reuse_count INT NULL COMMENT 'AI에 보낸 Feature: 같은 결제수단 고유 계정 수 (#67 이전 행은 NULL)',
    ADD COLUMN ip_booking_density INT NULL COMMENT 'AI에 보낸 Feature: 같은 IP 고유 계정 수 (#67 이전 행은 NULL)';

-- 2. 정책 결정의 서비스와 공연
ALTER TABLE policy_decision
    ADD COLUMN service_id VARCHAR(50) NULL COMMENT '정책을 요청한 외부 서비스. #67 이전 행은 NULL',
    ADD COLUMN concert_id VARCHAR(50) NULL COMMENT '정책을 요청한 공연. 요청에 없으면 NULL';
CREATE INDEX policy_decision_index_18 ON policy_decision (service_id, concert_id, decided_at);

-- 3. 현재 Credit의 최신 산출 근거 (FK는 credit_calculation 생성 뒤에 건다)
ALTER TABLE user_credit
    ADD COLUMN latest_calculation_id BIGINT NULL COMMENT '현재 점수를 만든 최신 산출 근거(credit_calculation). #67 이전 행은 NULL';

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
    service_id VARCHAR(50) NOT NULL,
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
