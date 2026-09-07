package com.example.sku_sw.global.aop;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class LoggingAspect {
    @Around("execution(* com.example.sku_sw.global.aop.OrderService.orderItem(..))")
    public Object logging(ProceedingJoinPoint joinPoint) throws Throwable {
        System.out.println("before");

        Object result = joinPoint.proceed();

        System.out.println("after");
        return result;
    }
}
