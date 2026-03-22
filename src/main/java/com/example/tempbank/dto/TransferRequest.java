package com.example.tempbank.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor
public class TransferRequest {

    @NotBlank(message = "출금 계좌번호는 필수입니다")
    private String fromAccount;

    @NotBlank(message = "입금 계좌번호는 필수입니다")
    private String toAccount;

    @DecimalMin(value = "0.01", message = "송금 금액은 0보다 커야 합니다")
    private BigDecimal amount;
}