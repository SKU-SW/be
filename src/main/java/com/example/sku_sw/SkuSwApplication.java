package com.example.sku_sw;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class SkuSwApplication {
    public static void main(String[] args) {
        SpringApplication.run(SkuSwApplication.class, args);
    }
}
