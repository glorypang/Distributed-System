package com.example.tempbank.config;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

public class ShardRoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        // ThreadLocal에서 현재 샤드 ID 가져오기
        return ShardContextHolder.getShardId();
    }
}