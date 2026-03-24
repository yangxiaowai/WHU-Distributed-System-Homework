package com.example.seckill.config.datasource;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Order(-10)
public class ReadOnlyAspect {

    @Around("@within(com.example.seckill.config.datasource.ReadOnly) || @annotation(com.example.seckill.config.datasource.ReadOnly)")
    public Object routeToRead(ProceedingJoinPoint pjp) throws Throwable {
        DataSourceContextHolder.use(DataSourceType.READ);
        try {
            return pjp.proceed();
        } finally {
            DataSourceContextHolder.clear();
        }
    }
}

