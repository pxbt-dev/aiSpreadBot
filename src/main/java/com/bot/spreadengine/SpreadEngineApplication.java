package com.bot.spreadengine;

import lombok.extern.slf4j.Slf4j;


import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@Slf4j
public class SpreadEngineApplication {

    public static void main(String[] args) {
        log.info("Starting Spread Engine Terminal...");
        SpringApplication.run(SpreadEngineApplication.class, args);
        log.info("Spread Engine Terminal started successfully.");

    }
}
