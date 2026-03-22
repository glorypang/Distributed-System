package com.example.tempbank.config;

public class ShardContextHolder {

    private static final ThreadLocal<Integer> CONTEXT = new ThreadLocal<>();

    public static void setShardId(Integer shardId) {
        CONTEXT.set(shardId);
    }

    public static Integer getShardId() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}