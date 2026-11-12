package com.job_search.fair_path;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
@EnableAsync
public class FairPathApplication {

	public static void main(String[] args) {
		SpringApplication.run(FairPathApplication.class, args);
	}

}
