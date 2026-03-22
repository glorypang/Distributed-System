package com.example.tempbank.saga;

import com.example.tempbank.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SagaEventHandler {

    private final SagaOrchestrator sagaOrchestrator;

    /**
     * 이벤트 처리 리스너 (Thread Pool 적용)
     */
    @RabbitListener(
            queues = RabbitMQConfig.EVENT_QUEUE,
            concurrency = "3-5"  // 최소 3개, 최대 5개 동시 처리
    )
    public void handleEvent(SagaEvent event) {
        String threadName = Thread.currentThread().getName();
        log.info("[{}] 이벤트 수신: {}", threadName, event);

        switch (event.getEventType()) {
            case DEBIT_SUCCESS -> sagaOrchestrator.onDebitSuccess(event.getSagaId());
            case DEBIT_FAILED -> sagaOrchestrator.onDebitFailed(event.getSagaId(), event.getMessage());
            case CREDIT_SUCCESS -> sagaOrchestrator.onCreditSuccess(event.getSagaId());
            case CREDIT_FAILED -> sagaOrchestrator.onCreditFailed(event.getSagaId(), event.getMessage());
            case COMPENSATE_SUCCESS -> sagaOrchestrator.onCompensateSuccess(event.getSagaId());
        }
    }
}