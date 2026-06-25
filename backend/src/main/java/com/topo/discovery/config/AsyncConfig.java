package com.topo.discovery.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Defineste executor-ul pentru @Async, rezolvand warning-ul
 * "More than one TaskExecutor bean found within the context".
 * Spring cauta un bean numit "taskExecutor" ca sa stie ce sa foloseasca pentru @Async.
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(4);
        exec.setMaxPoolSize(8);
        exec.setQueueCapacity(50);
        exec.setThreadNamePrefix("discovery-async-");
        exec.initialize();
        return exec;
    }
}
