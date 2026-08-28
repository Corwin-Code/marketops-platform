package com.mimococo.marketops.analyticsdecision.internal.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Local/CI invoke explicitly; runtime profiles schedule authorized internal export jobs. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "marketops.diagnostic-export", name = "worker-enabled", havingValue = "true")
public class DiagnosticExportScheduling {
    private final DiagnosticExportWorker worker;

    public DiagnosticExportScheduling(DiagnosticExportWorker worker) {
        this.worker = worker;
    }

    /** Fixed delay prevents overlapping passes in one process; DB fences cover other processes. */
    @Scheduled(initialDelay = 5000, fixedDelay = 2000)
    public void advance() {
        worker.runOnce();
    }
}
