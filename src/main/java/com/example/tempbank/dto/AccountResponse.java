package com.example.tempbank.dto;

import com.example.tempbank.domain.Account;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
public class AccountResponse {
    private final String accountNumber;
    private final String ownerName;
    private final BigDecimal balance;
    private final LocalDateTime createdAt;

    public AccountResponse(Account account) {
        this.accountNumber = account.getAccountNumber();
        this.ownerName = account.getOwnerName();
        this.balance = account.getBalance();
        this.createdAt = account.getCreatedAt();
    }
}