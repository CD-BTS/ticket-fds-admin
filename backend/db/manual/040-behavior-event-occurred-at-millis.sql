-- Apply once to an existing BTS database (#40). Review SHOW CREATE TABLE behavior_event first.
-- Widens behavior_event.occurred_at from DATETIME (seconds) to DATETIME(3) (milliseconds) so that
-- sub-second booking attempt intervals survive for AI features. Existing rows keep their value with .000.
-- Indexes on occurred_at are rebuilt by MySQL automatically. Do not rerun the complete schema.sql on an existing DB.
ALTER TABLE `behavior_event`
  MODIFY `occurred_at` datetime(3) NOT NULL COMMENT '외부 서비스에서 실제 행동이 발생한 시각 (UTC, 밀리초)';
