package com.job_search.fair_path.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.job_search.fair_path.services.AccountDeletionWorker;

@Configuration
@ConditionalOnProperty(
        name = "fairpath.account-deletion.scheduling-enabled", havingValue = "true")
public class AccountDeletionConfiguration {
    private final AccountDeletionWorker worker;

    public AccountDeletionConfiguration(AccountDeletionWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${fairpath.account-deletion.poll-interval}",
            scheduler = MaintenanceConfig.SCHEDULER)
    public void poll() {
        worker.run();
    }
}
