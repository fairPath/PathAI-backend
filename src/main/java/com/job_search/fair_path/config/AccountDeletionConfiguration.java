package com.job_search.fair_path.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.job_search.fair_path.services.AccountDeletionWorker;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@ConditionalOnProperty(
        name = "fairpath.account-deletion.scheduling-enabled", matchIfMissing = true)
public class AccountDeletionConfiguration {
    private final AccountDeletionWorker worker;

    public AccountDeletionConfiguration(AccountDeletionWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${fairpath.account-deletion.poll-interval:2s}",
            scheduler = "accountDeletionScheduler")
    public void poll() {
        worker.run();
    }

    @Bean
    ThreadPoolTaskScheduler accountDeletionScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("account-deletion-");
        return scheduler;
    }
}
