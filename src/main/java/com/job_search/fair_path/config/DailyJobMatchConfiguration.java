package com.job_search.fair_path.config;

import com.job_search.fair_path.services.DailyJobMatchService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@Profile("!test")
public class DailyJobMatchConfiguration {
    private final DailyJobMatchService service;

    public DailyJobMatchConfiguration(DailyJobMatchService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${fairpath.daily-job-match.poll-interval}", scheduler = "dailyJobMatchScheduler")
    public void poll() {
        service.poll();
    }

    @Bean
    ThreadPoolTaskScheduler dailyJobMatchScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("daily-job-match-");
        return scheduler;
    }
}
