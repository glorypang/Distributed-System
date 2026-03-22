package com.example.tempbank.saga;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SagaEvent implements Serializable {

    @JsonProperty("sagaId")
    private String sagaId;

    @JsonProperty("eventType")
    private EventType eventType;

    @JsonProperty("message")
    private String message;

    public enum EventType {
        DEBIT_SUCCESS,
        DEBIT_FAILED,
        CREDIT_SUCCESS,
        CREDIT_FAILED,
        COMPENSATE_SUCCESS
    }
}