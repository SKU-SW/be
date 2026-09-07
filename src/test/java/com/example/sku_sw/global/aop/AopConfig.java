package com.example.sku_sw.global.aop;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@Configuration
@ComponentScan("com.example.sku_sw.global.aop")
@EnableAspectJAutoProxy
public class AopConfig {
}
