-- #56: CHECK_IN 이벤트 기반 관람 이력 및 Credit 연동.
-- 기존 BTS DB에 한 번만 적용한다. 적용 전에 SHOW CREATE TABLE booking_history / user_credit 로 현재 구조를 확인한다.
-- 기존 행은 삭제·수정하지 않는다. 전체 schema.sql을 기존 DB에 다시 실행하지 않는다.
--
-- 재실행: MySQL에는 ADD COLUMN IF NOT EXISTS가 없다. 이미 적용된 DB에서 다시 실행하면 첫 문장이
--   "Duplicate column name 'service_id'" 로 실패하고 아무것도 바뀌지 않는다(각 ALTER는 문장 단위로 원자적이다).
--   첫 문장만 적용되고 중단됐다면 두 번째 ALTER(user_credit)만 따로 실행한다.
--
-- 기존 행과의 호환:
--   booking_history 기존 행은 service_id / ticket_id / event_ends_at 이 NULL로 남는다.
--   UNIQUE (service_id, ticket_id)는 NULL을 중복으로 보지 않으므로 기존 행끼리 충돌하지 않는다.
--   event_ends_at 이 NULL인 행은 NO_SHOW 자동 판정 대상이 아니다. status 값은 그대로 유지되어 Credit 집계에 계속 쓰인다.
--   user_credit 기존 행은 credit_score 가 바뀌지 않고 dynamic_penalty 가 NULL(감점 정보 미저장)로 남는다.

ALTER TABLE `booking_history`
  ADD COLUMN `service_id` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '외부 티켓팅 서비스 식별자. #56 이전 행은 NULL' AFTER `user_id`,
  ADD COLUMN `ticket_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '외부 티켓 ID. 관람 이력은 티켓 단위. #56 이전 행은 NULL' AFTER `service_id`,
  ADD COLUMN `event_ends_at` datetime NULL COMMENT '공연 종료 시각 (UTC, 예매 당시 값). NO_SHOW 판정 기준' AFTER `event_at`,
  MODIFY `booking_reference` varchar(100) NOT NULL COMMENT '외부 예매 고유 번호 (behavior_event.booking_id와 같은 길이)',
  MODIFY `status` varchar(20) NOT NULL COMMENT 'BOOKED, COMPLETED, NO_SHOW, AUTO_CANCELLED, CANCELLED',
  ADD UNIQUE KEY `uk_booking_service_ticket` (`service_id`, `ticket_id`),
  ADD INDEX `idx_booking_status_ends` (`status`, `event_ends_at`);

-- 신규 사용자 초기 Credit을 정책 값인 50으로 맞춘다. DEFAULT만 바꾸며 기존 행의 점수는 건드리지 않는다.
ALTER TABLE `user_credit`
  MODIFY `credit_score` int NOT NULL DEFAULT 50 COMMENT '관람 신용 점수 0~100, 초기값 50',
  ADD COLUMN `dynamic_penalty` double NULL COMMENT '마지막 평가에서 적용한 DynamicPenalty(AI+Rule, 0~70). 미저장이면 NULL' AFTER `credit_score`;
