package com.example.tempbank.domain;

public enum TransactionStatus {
    PENDING,      // 시작됨
    DEBITED,      // 출금 완료
    COMPLETED,    // 입금까지 완료
    FAILED,       // 실패
    COMPENSATED   // 보상 트랜잭션 완료 (롤백됨)
}