package com.example.seckill.meta;

import com.example.seckill.config.datasource.ReadOnly;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class MetaDbService {

    private final JdbcTemplate jdbcTemplate;

    public MetaDbService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> queryWriteDbMeta() {
        return jdbcTemplate.queryForMap("SELECT @@server_id AS server_id, @@read_only AS read_only, @@hostname AS hostname");
    }

    @ReadOnly
    public Map<String, Object> queryReadDbMeta() {
        return jdbcTemplate.queryForMap("SELECT @@server_id AS server_id, @@read_only AS read_only, @@hostname AS hostname");
    }
}

