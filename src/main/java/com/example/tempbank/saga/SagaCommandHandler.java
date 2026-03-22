package com.example.tempbank.saga;

import com.example.tempbank.config.RabbitMQConfig;
import com.example.tempbank.config.ShardContextHolder;
import com.example.tempbank.domain.Account;
import com.example.tempbank.repository.AccountRepository;
import com.example.tempbank.service.ShardingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class SagaCommandHandler {

    private final AccountRepository accountRepository;
    private final ShardingService shardingService;
    private final RabbitTemplate rabbitTemplate;

    // 멱등성 키: sagaId + commandType -> 처리 완료 여부
    private final Map<String, Boolean> processedCommands = new ConcurrentHashMap<>();

    // 재시도 설정
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 100;

    /**
     * 명령 처리 리스너 (Thread Pool 적용)
     */
    @RabbitListener(
            queues = RabbitMQConfig.COMMAND_QUEUE,
            concurrency = "5-10"  // 최소 5개, 최대 10개 동시 처리
    )
    @Transactional
    public void handleCommand(SagaCommand command) {
        String threadName = Thread.currentThread().getName();
        log.info("[{}] 명령 수신: {}", threadName, command);

        // 멱등성 키 생성
        String idempotencyKey = buildIdempotencyKey(command);

        // 멱등성 체크: 이미 처리된 명령인지 확인
        if (isAlreadyProcessed(idempotencyKey)) {
            log.info("[{}] 이미 처리된 명령 (멱등성 처리): {}", threadName, command);
            return;
        }

        try {
            switch (command.getCommandType()) {
                case DEBIT -> handleDebit(command);
                case CREDIT -> handleCredit(command);
                case COMPENSATE -> handleCompensate(command);
            }

            // 처리 완료 마킹
            markAsProcessed(idempotencyKey);

        } catch (OptimisticLockingFailureException e) {
            log.warn("[{}] 낙관적 락 충돌, 재시도: {}", threadName, command);
            handleWithRetry(command, 0);
        } catch (Exception e) {
            log.error("[{}] 명령 처리 실패: {}", threadName, command, e);
            handleFailure(command, e.getMessage());
        }
    }

    /**
     * 재시도 로직 (낙관적 락 충돌 시)
     */
    private void handleWithRetry(SagaCommand command, int retryCount) {
        if (retryCount >= MAX_RETRIES) {
            log.error("최대 재시도 횟수 초과: {}", command);
            handleFailure(command, "최대 재시도 횟수 초과");
            return;
        }

        try {
            Thread.sleep(RETRY_DELAY_MS * (retryCount + 1)); // 지수 백오프

            switch (command.getCommandType()) {
                case DEBIT -> handleDebit(command);
                case CREDIT -> handleCredit(command);
                case COMPENSATE -> handleCompensate(command);
            }

            String idempotencyKey = buildIdempotencyKey(command);
            markAsProcessed(idempotencyKey);

        } catch (OptimisticLockingFailureException e) {
            log.warn("재시도 중 낙관적 락 충돌 ({}회): {}", retryCount + 1, command);
            handleWithRetry(command, retryCount + 1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handleFailure(command, "재시도 중 인터럽트 발생");
        } catch (Exception e) {
            log.error("재시도 중 오류 발생: {}", command, e);
            handleFailure(command, e.getMessage());
        }
    }

    /**
     * 출금 처리 (멱등성 보장)
     */
    private void handleDebit(SagaCommand command) {
        int shardId = shardingService.getShardId(command.getAccountNumber());
        ShardContextHolder.setShardId(shardId);

        try {
            Account account = accountRepository.findByAccountNumber(command.getAccountNumber())
                    .orElseThrow(() -> new IllegalArgumentException("계좌를 찾을 수 없습니다: " + command.getAccountNumber()));

            // 멱등성 체크: Account 엔티티에 version이 있으므로 낙관적 락으로 동시성 제어됨
            boolean withdrawn = account.withdraw(command.getAmount());

            if (!withdrawn) {
                throw new IllegalStateException("출금 실패: 잔액 부족");
            }

            accountRepository.save(account);

            log.info("[{}] 출금 성공: {} (Shard {}), -{}원",
                    Thread.currentThread().getName(),
                    command.getAccountNumber(), shardId, command.getAmount());

            publishEvent(command.getSagaId(), SagaEvent.EventType.DEBIT_SUCCESS, "출금 성공");

        } finally {
            ShardContextHolder.clear();
        }
    }

    /**
     * 입금 처리 (멱등성 보장)
     */
    private void handleCredit(SagaCommand command) {
        int shardId = shardingService.getShardId(command.getAccountNumber());
        ShardContextHolder.setShardId(shardId);

        try {
            Account account = accountRepository.findByAccountNumber(command.getAccountNumber())
                    .orElseThrow(() -> new IllegalArgumentException("계좌를 찾을 수 없습니다: " + command.getAccountNumber()));

            // 멱등성 보장: deposit은 항상 성공하지만 version으로 동시성 제어
            account.deposit(command.getAmount());
            accountRepository.save(account);

            log.info("[{}] 입금 성공: {} (Shard {}), +{}원",
                    Thread.currentThread().getName(),
                    command.getAccountNumber(), shardId, command.getAmount());

            publishEvent(command.getSagaId(), SagaEvent.EventType.CREDIT_SUCCESS, "입금 성공");

        } finally {
            ShardContextHolder.clear();
        }
    }

    /**
     * 보상 트랜잭션 (출금 취소) (멱등성 보장)
     */
    private void handleCompensate(SagaCommand command) {
        int shardId = shardingService.getShardId(command.getAccountNumber());
        ShardContextHolder.setShardId(shardId);

        try {
            Account account = accountRepository.findByAccountNumber(command.getAccountNumber())
                    .orElseThrow(() -> new IllegalArgumentException("계좌를 찾을 수 없습니다: " + command.getAccountNumber()));

            // 보상: 출금했던 금액을 다시 입금
            account.deposit(command.getAmount());
            accountRepository.save(account);

            log.info("[{}] 보상 완료: {} (Shard {}), +{}원 (출금 취소)",
                    Thread.currentThread().getName(),
                    command.getAccountNumber(), shardId, command.getAmount());

            publishEvent(command.getSagaId(), SagaEvent.EventType.COMPENSATE_SUCCESS, "보상 완료");

        } finally {
            ShardContextHolder.clear();
        }
    }

    /**
     * 실패 처리
     */
    private void handleFailure(SagaCommand command, String reason) {
        SagaEvent.EventType eventType = switch (command.getCommandType()) {
            case DEBIT -> SagaEvent.EventType.DEBIT_FAILED;
            case CREDIT -> SagaEvent.EventType.CREDIT_FAILED;
            default -> null;
        };

        if (eventType != null) {
            publishEvent(command.getSagaId(), eventType, reason);
        }
    }

    /**
     * 이벤트 발행 (멱등성 보장)
     */
    private void publishEvent(String sagaId, SagaEvent.EventType eventType, String message) {
        SagaEvent event = new SagaEvent(sagaId, eventType, message);

        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.SAGA_EXCHANGE,
                    RabbitMQConfig.EVENT_ROUTING_KEY,
                    event
            );
            log.info("[{}] 이벤트 발행: {}", Thread.currentThread().getName(), event);
        } catch (Exception e) {
            log.error("[{}] 이벤트 발행 실패: {}", Thread.currentThread().getName(), event, e);
            // 이벤트 발행 실패는 재시도 큐로 보내거나 Dead Letter Queue로 처리
            throw new RuntimeException("이벤트 발행 실패", e);
        }
    }

    // === 멱등성 헬퍼 메서드 ===

    /**
     * 멱등성 키 생성
     */
    private String buildIdempotencyKey(SagaCommand command) {
        return command.getSagaId() + ":" + command.getCommandType().name();
    }

    /**
     * 이미 처리된 명령인지 확인
     */
    private boolean isAlreadyProcessed(String idempotencyKey) {
        return processedCommands.getOrDefault(idempotencyKey, false);
    }

    /**
     * 처리 완료 마킹
     */
    private void markAsProcessed(String idempotencyKey) {
        processedCommands.put(idempotencyKey, true);
    }

    /**
     * 처리 완료 기록 정리 (메모리 관리)
     * 주기적으로 호출하여 오래된 키 제거
     */
    public void cleanupProcessedCommands() {
        // TTL 기반 정리 로직 추가 가능
        // 예: 1시간 이상 된 키 제거
        log.info("처리 완료 명령 정리: 현재 크기 = {}", processedCommands.size());
    }
}