package com.bts.fairticket.credit.dto;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.data.domain.Page;
import com.bts.fairticket.credit.entity.CreditHistory;

public record CreditHistoryResponse(
    String userId,
    List<HistoryItem> histories,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
    public record HistoryItem(
        Long historyId,
        Long eventId,
        Integer scoreBefore,
        Integer scoreAfter,
        String reason,
        String createdAt
    ) {
        public static HistoryItem from(CreditHistory entity) {
            Long eventId = (entity.getEvent() != null) ? entity.getEvent().getEventId() : null;
            String formattedCreatedAt = null;
            if (entity.getCreatedAt() != null) {
                formattedCreatedAt = entity.getCreatedAt()
                        .atOffset(java.time.ZoneOffset.UTC) // DB 시각은 UTC다. +09:00으로 잘못 붙이던 표기를 Z로 바로잡았다 (#67)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            }
            return new HistoryItem(
                entity.getHistoryId(),
                eventId,
                entity.getScoreBefore(),
                entity.getScoreAfter(),
                entity.getReason(),
                formattedCreatedAt
            );
        }
    }

    public static CreditHistoryResponse of(String userId, Page<CreditHistory> page) {
        List<HistoryItem> list = page.getContent().stream()
                .map(HistoryItem::from)
                .toList();
        return new CreditHistoryResponse(
            userId,
            list,
            page.getNumber(),
            page.getSize(),
            page.getTotalElements(),
            page.getTotalPages()
        );
    }
}