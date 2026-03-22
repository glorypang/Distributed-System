package com.example.tempbank.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ShardingService {

    @Value("${sharding.shard-count}")
    private int shardCount;

    /**
     * 계좌번호로 샤드 ID 결정 (해시 기반)
     */
    public int getShardId(String accountNumber) {
        int hash = Math.abs(accountNumber.hashCode());
        return hash % shardCount;
    }
}