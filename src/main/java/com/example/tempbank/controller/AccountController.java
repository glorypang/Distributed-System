package com.example.tempbank.controller;

import com.example.tempbank.domain.Transaction;
import com.example.tempbank.dto.AccountCreateRequest;
import com.example.tempbank.dto.AccountResponse;
import com.example.tempbank.dto.TransferRequest;
import com.example.tempbank.repository.TransactionRepository;
import com.example.tempbank.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {
    private final TransactionRepository transactionRepository;
    private final AccountService accountService;

    /**
     * 계좌 생성
     * POST /api/accounts
     */
    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody AccountCreateRequest request) {
        AccountResponse response = accountService.createAccount(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 잔액 조회
     * GET /api/accounts/{accountNumber}
     */
    @GetMapping("/{accountNumber}")
    public ResponseEntity<AccountResponse> getAccount(@PathVariable String accountNumber) {
        AccountResponse response = accountService.getAccount(accountNumber);
        return ResponseEntity.ok(response);
    }

    /**
     * 송금
     * POST /api/accounts/transfer
     */
    @PostMapping("/transfer")
    public ResponseEntity<Map<String, String>> transfer(@Valid @RequestBody TransferRequest request) {
        String result = accountService.transfer(request);

        if ("SAME_SHARD_SUCCESS".equals(result)) {
            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "message", "송금이 완료되었습니다"
            ));
        } else {
            // Saga ID 반환 (비동기 처리)
            return ResponseEntity.ok(Map.of(
                    "status", "PENDING",
                    "sagaId", result,
                    "message", "송금 처리 중입니다. 잠시 후 확인해주세요."
            ));
        }
    }
    /**
     * 거래 상태 확인 (디버깅용)
     */
    @GetMapping("/transactions/status")
    public ResponseEntity<Map<String, Object>> getTransactionStats() {
        List<Transaction> all = transactionRepository.findAll();

        long pending = all.stream().filter(t -> t.getStatus().name().equals("PENDING")).count();
        long debited = all.stream().filter(t -> t.getStatus().name().equals("DEBITED")).count();
        long completed = all.stream().filter(t -> t.getStatus().name().equals("COMPLETED")).count();
        long failed = all.stream().filter(t -> t.getStatus().name().equals("FAILED")).count();
        long compensated = all.stream().filter(t -> t.getStatus().name().equals("COMPENSATED")).count();

        return ResponseEntity.ok(Map.of(
                "total", all.size(),
                "PENDING", pending,
                "DEBITED", debited,
                "COMPLETED", completed,
                "FAILED", failed,
                "COMPENSATED", compensated
        ));
    }
}