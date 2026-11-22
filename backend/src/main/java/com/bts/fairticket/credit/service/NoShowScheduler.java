package com.bts.fairticket.credit.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** NO_SHOW 판정을 주기적으로 실행한다. bts.no-show.enabled=false면 등록되지 않는다. */
@Slf4j
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "bts.no-show.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class NoShowScheduler {

    private final NoShowService noShowService;

    @Scheduled(initialDelayString = "${bts.no-show.check-interval-ms:60000}",
            fixedDelayString = "${bts.no-show.check-interval-ms:60000}")
    public void run() {
        try {
            int marked = noShowService.markNoShows();
            if (marked > 0) {
                log.info("Marked {} ticket(s) as NO_SHOW", marked);
            }
        } catch (RuntimeException exception) {
            log.error("NO_SHOW check failed", exception);
        }
    }
}
