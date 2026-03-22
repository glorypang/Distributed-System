package com.example.tempbank.service;

import com.example.tempbank.config.ShardContextHolder;
import com.example.tempbank.domain.Account;
import com.example.tempbank.dto.AccountCreateRequest;
import com.example.tempbank.dto.AccountResponse;
import com.example.tempbank.dto.TransferRequest;
import com.example.tempbank.repository.AccountRepository;
import com.example.tempbank.saga.SagaOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final ShardingService shardingService;
    private final SagaOrchestrator sagaOrchestrator;  // 추가

    /**
     * 계좌 생성 (샤드 자동 결정)
     */
    @Transactional
    public AccountResponse createAccount(AccountCreateRequest request) {
        Account account = Account.builder()
                .ownerName(request.getOwnerName())
                .initialBalance(request.getInitialBalance())
                .build();

        // 계좌번호 생성 후 샤드 결정
        int shardId = shardingService.getShardId(account.getAccountNumber());
        ShardContextHolder.setShardId(shardId);

        try {
            Account saved = accountRepository.save(account);
            log.info("계좌 생성 완료: {} (Shard {})", saved.getAccountNumber(), shardId);
            return new AccountResponse(saved);
        } finally {
            ShardContextHolder.clear();
        }
    }

    /**
     * 잔액 조회 (샤드 라우팅)
     */
    @Transactional(readOnly = true)
    public AccountResponse getAccount(String accountNumber) {
        int shardId = shardingService.getShardId(accountNumber);
        ShardContextHolder.setShardId(shardId);

        try {
            Account account = accountRepository.findByAccountNumber(accountNumber)
                    .orElseThrow(() -> new IllegalArgumentException("계좌를 찾을 수 없습니다: " + accountNumber));

            log.info("계좌 조회: {} (Shard {})", accountNumber, shardId);
            return new AccountResponse(account);
        } finally {
            ShardContextHolder.clear();
        }
    }

    /**
     * 송금 (Saga 패턴 적용)
     */
    @Transactional
    public String transfer(TransferRequest request) {
        int fromShardId = shardingService.getShardId(request.getFromAccount());
        int toShardId = shardingService.getShardId(request.getToAccount());

        // Cross-shard 송금: Saga 패턴 사용
        if (fromShardId != toShardId) {
            log.info("Cross-shard 송금 감지: Shard {} → Shard {}", fromShardId, toShardId);
            String sagaId = sagaOrchestrator.startTransfer(
                    request.getFromAccount(),
                    request.getToAccount(),
                    request.getAmount()
            );
            return sagaId;
        }

        // 같은 샤드 내 송금: 기존 방식
        ShardContextHolder.setShardId(fromShardId);

        try {
            Account fromAccount = accountRepository.findByAccountNumber(request.getFromAccount())
                    .orElseThrow(() -> new IllegalArgumentException("출금 계좌를 찾을 수 없습니다"));

            Account toAccount = accountRepository.findByAccountNumber(request.getToAccount())
                    .orElseThrow(() -> new IllegalArgumentException("입금 계좌를 찾을 수 없습니다"));

            fromAccount.withdraw(request.getAmount());
            toAccount.deposit(request.getAmount());

            log.info("송금 완료: {} → {} ({}원, Shard {})",
                    request.getFromAccount(),
                    request.getToAccount(),
                    request.getAmount(),
                    fromShardId);

            return "SAME_SHARD_SUCCESS";  // 같은 샤드는 즉시 완료

        } finally {
            ShardContextHolder.clear();
        }
    }
}