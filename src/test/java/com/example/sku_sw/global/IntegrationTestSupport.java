package com.example.sku_sw.global;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 통합 테스트 시 테스트 클래스가 상속받을 부모 클래스
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {
}
