package com.example.tempbank.saga;

import com.example.tempbank.config.RabbitMQConfig;
import com.example.tempbank.domain.Transaction;
import com.example.tempbank.domain.TransactionStatus;
import com.example.tempbank.repository.TransactionRepository;
import com.example.tempbank.service.ShardingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SagaOrchestrator {

    private final RabbitTemplate rabbitTemplate;
    private final TransactionRepository transactionRepository;
    private final ShardingService shardingService;

    /**
     * Cross-Shard 송금 시작 (멱등성 보장)
     */
    @Transactional
    public String startTransfer(String fromAccount, String toAccount, BigDecimal amount) {
        return startTransfer(UUID.randomUUID().toString(), fromAccount, toAccount, amount);
    }

    /**
     * Cross-Shard 송금 시작 (외부에서 sagaId 제공 시 멱등성 보장)
     */
    @Transactional
    public String startTransfer(String sagaId, String fromAccount, String toAccount, BigDecimal amount) {
        // 멱등성 체크: 이미 존재하는 sagaId인지 확인
        Optional<Transaction> existingTx = transactionRepository.findBySagaId(sagaId);
        if (existingTx.isPresent()) {
            Transaction tx = existingTx.get();
            log.info("이미 존재하는 Saga: sagaId={}, status={} (멱등성 처리)", sagaId, tx.getStatus());

            // PENDING 상태라면 재시도로 간주하고 명령 재발행
            if (tx.getStatus() == TransactionStatus.PENDING) {
                log.info("PENDING 상태 Saga 재시도: sagaId={}", sagaId);
                resendDebitCommand(tx);
            }

            return sagaId;
        }

        int fromShardId = shardingService.getShardId(fromAccount);
        int toShardId = shardingService.getShardId(toAccount);

        // Transaction 기록 생성
        Transaction transaction = Transaction.builder()
                .sagaId(sagaId)
                .fromAccount(fromAccount)
                .toAccount(toAccount)
                .amount(amount)
                .fromShardId(fromShardId)
                .toShardId(toShardId)
                .status(TransactionStatus.PENDING)
                .build();

        try {
            transactionRepository.save(transaction);
        } catch (DataIntegrityViolationException e) {
            // 동시성 경합으로 인한 중복 생성 시도 시 기존 거래 반환
            log.warn("동시성 경합 감지: sagaId={}, 기존 거래 사용", sagaId);
            return sagaId;
        }

        log.info("=== Saga 시작 === sagaId: {}, {}(Shard {}) → {}(Shard {}), {}원",
                sagaId, fromAccount, fromShardId, toAccount, toShardId, amount);

        // 1단계: 출금 명령 발행
        sendDebitCommand(transaction);

        return sagaId;
    }

    /**
     * 출금 성공 → 입금 명령 발행 (멱등성 보장)
     */
    @Transactional
    public void onDebitSuccess(String sagaId) {
        Transaction transaction = transactionRepository.findBySagaId(sagaId)
                .orElseThrow(() -> new IllegalArgumentException("거래를 찾을 수 없습니다: " + sagaId));

        // 멱등성 체크
        if (!transaction.markAsDebited()) {
            log.info("이미 출금 완료된 거래: sagaId={}, status={} (멱등성 처리)",
                    sagaId, transaction.getStatus());

            // DEBITED 상태라면 입금 명령 재발행 (네트워크 장애로 명령이 유실되었을 수 있음)
            if (transaction.getStatus() == TransactionStatus.DEBITED) {
                resendCreditCommand(transaction);
            }
            return;
        }

        transactionRepository.save(transaction);
        log.info("출금 성공 → 입금 명령 발행: sagaId={}", sagaId);

        // 2단계: 입금 명령 발행
        sendCreditCommand(transaction);
    }

    /**
     * 입금 성공 → 송금 완료 (멱등성 보장)
     */
    @Transactional
    public void onCreditSuccess(String sagaId) {
        Transaction transaction = transactionRepository.findBySagaId(sagaId)
                .orElseThrow(() -> new IllegalArgumentException("거래를 찾을 수 없습니다: " + sagaId));

        // 멱등성 체크
        if (!transaction.markAsCompleted()) {
            log.info("이미 완료된 거래: sagaId={}, status={} (멱등성 처리)",
                    sagaId, transaction.getStatus());
            return;
        }

        transactionRepository.save(transaction);
        log.info("=== Saga 완료 === sagaId: {}, 송금 성공!", sagaId);
    }

    /**
     * 출금 실패 → Saga 실패 (멱등성 보장)
     */
    @Transactional
    public void onDebitFailed(String sagaId, String reason) {
        Transaction transaction = transactionRepository.findBySagaId(sagaId)
                .orElseThrow(() -> new IllegalArgumentException("거래를 찾을 수 없습니다: " + sagaId));

        // 멱등성 체크
        if (!transaction.markAsFailed(reason)) {
            log.info("이미 실패 처리된 거래: sagaId={}, status={} (멱등성 처리)",
                    sagaId, transaction.getStatus());
            return;
        }

        transactionRepository.save(transaction);
        log.error("=== Saga 실패 === sagaId: {}, 이유: {}", sagaId, reason);
    }

    /**
     * 입금 실패 → 보상 트랜잭션 (멱등성 보장)
     */
    @Transactional
    public void onCreditFailed(String sagaId, String reason) {
        Transaction transaction = transactionRepository.findBySagaId(sagaId)
                .orElseThrow(() -> new IllegalArgumentException("거래를 찾을 수 없습니다: " + sagaId));

        log.warn("입금 실패! 보상 트랜잭션 시작: sagaId={}, 이유={}", sagaId, reason);

        // 멱등성 체크: 이미 보상 중이거나 완료된 경우
        if (transaction.getStatus() == TransactionStatus.COMPENSATED) {
            log.info("이미 보상 완료된 거래: sagaId={} (멱등성 처리)", sagaId);
            return;
        }

        if (transaction.getStatus() == TransactionStatus.FAILED) {
            log.info("이미 실패 처리된 거래: sagaId={} (멱등성 처리)", sagaId);
            return;
        }

        // 보상 명령 발행 (출금 취소)
        sendCompensateCommand(transaction);
    }

    /**
     * 보상 트랜잭션 완료 (멱등성 보장)
     */
    @Transactional
    public void onCompensateSuccess(String sagaId) {
        Transaction transaction = transactionRepository.findBySagaId(sagaId)
                .orElseThrow(() -> new IllegalArgumentException("거래를 찾을 수 없습니다: " + sagaId));

        // 멱등성 체크
        if (!transaction.markAsCompensated()) {
            log.info("이미 보상 완료된 거래: sagaId={}, status={} (멱등성 처리)",
                    sagaId, transaction.getStatus());
            return;
        }

        transactionRepository.save(transaction);
        log.info("=== Saga 롤백 완료 === sagaId: {}, 출금 취소됨", sagaId);
    }

    // === 명령 발행 헬퍼 메서드 (재사용성 향상) ===

    private void sendDebitCommand(Transaction transaction) {
        SagaCommand debitCommand = new SagaCommand(
                transaction.getSagaId(),
                transaction.getFromAccount(),
                transaction.getAmount(),
                SagaCommand.CommandType.DEBIT
        );

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.SAGA_EXCHANGE,
                RabbitMQConfig.COMMAND_ROUTING_KEY,
                debitCommand
        );

        log.info("출금 명령 발행: {}", debitCommand);
    }

    private void sendCreditCommand(Transaction transaction) {
        SagaCommand creditCommand = new SagaCommand(
                transaction.getSagaId(),
                transaction.getToAccount(),
                transaction.getAmount(),
                SagaCommand.CommandType.CREDIT
        );

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.SAGA_EXCHANGE,
                RabbitMQConfig.COMMAND_ROUTING_KEY,
                creditCommand
        );
    }

    private void sendCompensateCommand(Transaction transaction) {
        SagaCommand compensateCommand = new SagaCommand(
                transaction.getSagaId(),
                transaction.getFromAccount(),
                transaction.getAmount(),
                SagaCommand.CommandType.COMPENSATE
        );

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.SAGA_EXCHANGE,
                RabbitMQConfig.COMMAND_ROUTING_KEY,
                compensateCommand
        );
    }

    private void resendDebitCommand(Transaction transaction) {
        log.info("출금 명령 재발행: sagaId={}", transaction.getSagaId());
        sendDebitCommand(transaction);
    }

    private void resendCreditCommand(Transaction transaction) {
        log.info("입금 명령 재발행: sagaId={}", transaction.getSagaId());
        sendCreditCommand(transaction);
    }
}