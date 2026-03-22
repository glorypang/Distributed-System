package com.example.tempbank.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor
public class AccountCreateRequest {

    @NotBlank(message = "소유자 이름은 필수입니다")
    private String ownerName;

    @DecimalMin(value = "0.0", message = "초기 잔액은 0 이상이어야 합니다")
    private BigDecimal initialBalance;
}