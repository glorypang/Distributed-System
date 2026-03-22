package com.example.tempbank.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 송금 처리용 Thread Pool
     */
    @Bean(name = "transferExecutor")
    public Executor transferExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // Core Pool Size: 기본적으로 유지할 스레드 수
        executor.setCorePoolSize(10);

        // Max Pool Size: 최대 스레드 수
        executor.setMaxPoolSize(50);

        // Queue Capacity: 대기 큐 크기
        executor.setQueueCapacity(100);

        // Thread 이름 prefix
        executor.setThreadNamePrefix("Transfer-");

        // 거부 정책: 큐가 가득 차면 호출한 스레드에서 직접 실행
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // Keep-Alive Time: 유휴 스레드 유지 시간
        executor.setKeepAliveSeconds(60);

        // 종료 시 모든 작업 완료 대기
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);

        executor.initialize();

        log.info("Transfer Thread Pool 초기화: Core={}, Max={}, Queue={}",
                executor.getCorePoolSize(),
                executor.getMaxPoolSize(),
                executor.getQueueCapacity());

        return executor;
    }

    /**
     * Saga Command 처리용 Thread Pool
     */
    @Bean(name = "sagaExecutor")
    public Executor sagaExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("Saga-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        executor.initialize();

        log.info("Saga Thread Pool 초기화: Core={}, Max={}, Queue={}",
                executor.getCorePoolSize(),
                executor.getMaxPoolSize(),
                executor.getQueueCapacity());

        return executor;
    }
}