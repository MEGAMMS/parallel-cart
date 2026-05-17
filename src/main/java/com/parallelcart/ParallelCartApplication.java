package com.parallelcart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ParallelCartApplication {

	public static void main(String[] args) {
		SpringApplication.run(ParallelCartApplication.class, args);
	}

}
