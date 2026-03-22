package com.example.tempbank.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "transactions", indexes = {
        @Index(name = "idx_saga_id", columnList = "sagaId"),
        @Index(name = "idx_status", columnList = "status"),
        @Index(name = "idx_from_account", columnList = "fromAccount"),
        @Index(name = "idx_to_account", columnList = "toAccount")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 100)
    private String sagaId; // Saga 고유 ID (멱등성 키)

    @Column(nullable = false, length = 50)
    private String fromAccount;

    @Column(nullable = false, length = 50)
    private String toAccount;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionStatus status;

    @Column(nullable = false)
    private Integer fromShardId;

    @Column(nullable = false)
    private Integer toShardId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime completedAt;

    @Column
    private LocalDateTime lastUpdatedAt;

    @Version // 낙관적 락으로 동시성 제어
    private Long version;

    @Column(length = 500)
    private String failureReason; // 실패 사유 추적

    @Column
    private Integer retryCount; // 재시도 횟수 추적

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        lastUpdatedAt = LocalDateTime.now();
        retryCount = 0;
    }

    @PreUpdate
    protected void onUpdate() {
        lastUpdatedAt = LocalDateTime.now();
    }

    // 멱등성을 보장하는 상태 변경 메서드
    public boolean markAsDebited() {
        if (this.status == TransactionStatus.DEBITED ||
                this.status == TransactionStatus.COMPLETED) {
            return false; // 이미 처리됨 (멱등성)
        }
        if (this.status != TransactionStatus.PENDING) {
            throw new IllegalStateException(
                    "Cannot debit transaction in status: " + this.status
            );
        }
        this.status = TransactionStatus.DEBITED;
        return true;
    }

    public boolean markAsCompleted() {
        if (this.status == TransactionStatus.COMPLETED) {
            return false; // 이미 완료됨 (멱등성)
        }
        if (this.status != TransactionStatus.DEBITED) {
            throw new IllegalStateException(
                    "Cannot complete transaction in status: " + this.status
            );
        }
        this.status = TransactionStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
        return true;
    }

    public boolean markAsFailed(String reason) {
        if (this.status == TransactionStatus.FAILED ||
                this.status == TransactionStatus.COMPENSATED) {
            return false; // 이미 실패 처리됨 (멱등성)
        }
        this.status = TransactionStatus.FAILED;
        this.failureReason = reason;
        this.completedAt = LocalDateTime.now();
        return true;
    }

    public boolean markAsCompensated() {
        if (this.status == TransactionStatus.COMPENSATED) {
            return false; // 이미 보상됨 (멱등성)
        }
        if (this.status != TransactionStatus.DEBITED &&
                this.status != TransactionStatus.FAILED) {
            throw new IllegalStateException(
                    "Cannot compensate transaction in status: " + this.status
            );
        }
        this.status = TransactionStatus.COMPENSATED;
        this.completedAt = LocalDateTime.now();
        return true;
    }

    public void incrementRetryCount() {
        this.retryCount = (this.retryCount == null ? 0 : this.retryCount) + 1;
    }

    // 최대 재시도 횟수 체크
    public boolean hasExceededMaxRetries(int maxRetries) {
        return this.retryCount != null && this.retryCount >= maxRetries;
    }
}