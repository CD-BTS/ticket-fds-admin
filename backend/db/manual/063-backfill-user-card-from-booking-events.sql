-- Optional, apply once to an existing BTS database (#63). No table is created or altered.
-- From #63 on, each new BOOKING event records its payment identifier in user_card so that the Credit card-sharing
-- rule (distinct accounts per card hash) has data. This statement fills user_card for BOOKING events stored before that.
-- card_hash = SHA-256("<service_id>:<paymentIdentifier>") as 64 lowercase hex characters, the same value the
-- application writes (BookingHistoryService.cardHash). Rows that already exist are skipped, so rerunning is safe.
-- Existing Credit scores are not changed by this statement; the penalty applies at each user's next Credit evaluation.
INSERT INTO user_card (user_id, card_hash, last_used_at, created_at)
SELECT b.user_id, b.card_hash, MAX(b.occurred_at), MIN(b.occurred_at)
FROM (
    SELECT e.user_id,
           SHA2(CONCAT(e.service_id, ':', TRIM(JSON_UNQUOTE(JSON_EXTRACT(e.metadata, '$.paymentIdentifier')))), 256) AS card_hash,
           e.occurred_at
    FROM behavior_event e
    WHERE e.event_type = 'BOOKING'
      AND JSON_TYPE(JSON_EXTRACT(e.metadata, '$.paymentIdentifier')) = 'STRING'
      AND TRIM(JSON_UNQUOTE(JSON_EXTRACT(e.metadata, '$.paymentIdentifier'))) <> ''
) b
WHERE NOT EXISTS (
    SELECT 1 FROM user_card c WHERE c.user_id = b.user_id AND c.card_hash = b.card_hash
)
GROUP BY b.user_id, b.card_hash;
