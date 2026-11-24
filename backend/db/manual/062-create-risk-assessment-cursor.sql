-- Apply once to an existing BTS database (#40). Adds the AI risk assessment cursor table; no existing tables are changed.
-- Without it, enabling bts.ai.enabled=true fails on the first run. Do not rerun the complete schema.sql on an existing DB.
CREATE TABLE `risk_assessment_cursor` (
  `user_id` varchar(50) PRIMARY KEY COMMENT 'BTS 내부 사용자 ID',
  `last_event_id` bigint NOT NULL COMMENT 'AI 평가에 마지막으로 반영한 behavior_event.event_id',
  `last_status` varchar(20) NOT NULL COMMENT 'OK, INSUFFICIENT_DATA, INVALID',
  `checked_at` datetime NOT NULL COMMENT '마지막 평가 시각 (UTC)'
);
