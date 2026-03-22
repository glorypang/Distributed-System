package com.example.tempbank.saga;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SagaCommand implements Serializable {

    @JsonProperty("sagaId")
    private String sagaId;

    @JsonProperty("accountNumber")
    private String accountNumber;

    @JsonProperty("amount")
    private BigDecimal amount;

    @JsonProperty("commandType")
    private CommandType commandType;

    public enum CommandType {
        DEBIT,
        CREDIT,
        COMPENSATE
    }
}