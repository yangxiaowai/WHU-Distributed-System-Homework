package com.example.seckill.config.datasource;

public final class DataSourceContextHolder {

    private static final ThreadLocal<DataSourceType> CONTEXT = new ThreadLocal<>();

    private DataSourceContextHolder() {}

    public static void use(DataSourceType type) {
        CONTEXT.set(type);
    }

    public static DataSourceType get() {
        DataSourceType type = CONTEXT.get();
        return type == null ? DataSourceType.WRITE : type;
    }

    public static void clear() {
        CONTEXT.remove();
    }
}

