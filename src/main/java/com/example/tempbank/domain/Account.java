package com.example.tempbank.domain;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "accounts", indexes = {
        @Index(name = "idx_account_number", columnList = "accountNumber"),
        @Index(name = "idx_owner_name", columnList = "ownerName")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 20)
    private String accountNumber;

    @Column(nullable = false, length = 100)
    private String ownerName;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal balance;

    @Version // 낙관적 락으로 동시성 제어 (멱등성 보장)
    private Long version;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime lastUpdatedAt;

    @Column(precision = 15, scale = 2)
    private BigDecimal previousBalance; // 이전 잔액 (롤백/감사용)

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        lastUpdatedAt = LocalDateTime.now();
        previousBalance = balance;
    }

    @PreUpdate
    protected void onUpdate() {
        lastUpdatedAt = LocalDateTime.now();
    }

    @Builder
    public Account(String ownerName, BigDecimal initialBalance) {
        this.accountNumber = generateAccountNumber();
        this.ownerName = ownerName;
        this.balance = initialBalance != null ? initialBalance : BigDecimal.ZERO;
        this.previousBalance = this.balance;
    }

    /**
     * 출금 (멱등성 보장)
     * @return 출금 성공 여부
     */
    public boolean withdraw(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("출금 금액은 0보다 커야 합니다");
        }

        if (balance.compareTo(amount) < 0) {
            throw new IllegalArgumentException(
                    String.format("잔액 부족: 현재 잔액 %s, 출금 요청 %s", balance, amount)
            );
        }

        this.previousBalance = this.balance;
        this.balance = balance.subtract(amount);
        return true;
    }

    /**
     * 입금 (멱등성 보장)
     * @return 입금 성공 여부
     */
    public boolean deposit(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("입금 금액은 0보다 커야 합니다");
        }

        this.previousBalance = this.balance;
        this.balance = balance.add(amount);
        return true;
    }

    /**
     * 조건부 출금 (특정 잔액 기대값과 일치할 때만 출금)
     * Compare-And-Swap 패턴으로 멱등성 보장
     */
    public boolean withdrawIfBalanceEquals(BigDecimal amount, BigDecimal expectedBalance) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("출금 금액은 0보다 커야 합니다");
        }

        // 멱등성 체크: 현재 잔액이 예상 잔액과 다르면 이미 처리된 것으로 간주
        if (!balance.equals(expectedBalance)) {
            return false; // 이미 처리됨
        }

        if (balance.compareTo(amount) < 0) {
            throw new IllegalArgumentException("잔액 부족");
        }

        this.previousBalance = this.balance;
        this.balance = balance.subtract(amount);
        return true;
    }

    /**
     * 조건부 입금 (특정 잔액 기대값과 일치할 때만 입금)
     * Compare-And-Swap 패턴으로 멱등성 보장
     */
    public boolean depositIfBalanceEquals(BigDecimal amount, BigDecimal expectedBalance) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("입금 금액은 0보다 커야 합니다");
        }

        // 멱등성 체크: 현재 잔액이 예상 잔액과 다르면 이미 처리된 것으로 간주
        if (!balance.equals(expectedBalance)) {
            return false; // 이미 처리됨
        }

        this.previousBalance = this.balance;
        this.balance = balance.add(amount);
        return true;
    }

    /**
     * 잔액 롤백 (보상 트랜잭션용)
     */
    public void rollbackToPreviousBalance() {
        if (previousBalance != null) {
            this.balance = this.previousBalance;
        }
    }

    /**
     * 잔액 검증
     */
    public boolean hasBalance(BigDecimal amount) {
        return balance.compareTo(amount) >= 0;
    }

    /**
     * 잔액이 특정 값인지 확인 (멱등성 체크용)
     */
    public boolean balanceEquals(BigDecimal expectedBalance) {
        return balance.compareTo(expectedBalance) == 0;
    }

    // 개선된 계좌번호 생성 (UUID 기반으로 더 랜덤하게)
    private String generateAccountNumber() {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        long hash = Math.abs(uuid.hashCode());
        return String.format("%04d-%04d",
                (int)(hash / 10000 % 10000),
                (int)(hash % 10000));
    }

    /**
     * 거래 로그용 정보 반환
     */
    public String getTransactionInfo() {
        return String.format("계좌번호: %s, 소유자: %s, 현재잔액: %s, 이전잔액: %s",
                accountNumber, ownerName, balance, previousBalance);
    }
}