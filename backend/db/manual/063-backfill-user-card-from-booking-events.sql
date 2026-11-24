-- Optional, apply once to an existing BTS database (#63). No table is created or altered.
-- From #63 on, each new BOOKING event records its payment identifier in user_card so that the Credit card-sharing
-- rule (distinct accounts per card hash) has data. This statement fills user_card for BOOKING events stored before that.
--
-- It follows the application rule (BookingHistoryService.cardHash / recordPaymentCard) exactly; change both together.
--   identifier  = metadata.paymentIdentifier, JSON strings only, with leading/trailing space, tab, CR and LF removed;
--                 skipped when empty after that
--   card_hash   = SHA-256("<UTF-8 byte length of service_id>:<service_id>:<identifier>"), 64 lowercase hex characters
--   last_used_at = the newest BOOKING time for that user and card, truncated (not rounded) to seconds
--   created_at  = left to the column default (the time the row is written), as for rows the application inserts
-- One row per user and card. Rows that already exist are skipped, so rerunning inserts nothing new.
-- Existing Credit scores are not changed by this statement; the penalty applies at each user's next Credit evaluation.
INSERT INTO user_card (user_id, card_hash, last_used_at)
SELECT b.user_id, b.card_hash, CAST(DATE_FORMAT(MAX(b.occurred_at), '%Y-%m-%d %H:%i:%s') AS DATETIME)
FROM (
    SELECT n.user_id,
           SHA2(CONCAT(LENGTH(CONVERT(n.service_id USING utf8mb4)), ':', n.service_id, ':', n.identifier), 256) AS card_hash,
           n.occurred_at
    FROM (
        SELECT e.user_id, e.service_id, e.occurred_at,
               REGEXP_REPLACE(JSON_UNQUOTE(JSON_EXTRACT(e.metadata, '$.paymentIdentifier')),
                              '^[ \\t\\r\\n]+|[ \\t\\r\\n]+$', '') AS identifier
        FROM behavior_event e
        WHERE e.event_type = 'BOOKING'
          AND JSON_TYPE(JSON_EXTRACT(e.metadata, '$.paymentIdentifier')) = 'STRING'
    ) n
    WHERE n.identifier <> ''
) b
WHERE NOT EXISTS (
    SELECT 1 FROM user_card c WHERE c.user_id = b.user_id AND c.card_hash = b.card_hash
)
GROUP BY b.user_id, b.card_hash;
