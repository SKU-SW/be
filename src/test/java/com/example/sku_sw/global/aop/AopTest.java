package com.example.sku_sw.global.aop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = AopConfig.class)
@ExtendWith(OutputCaptureExtension.class)
// OutputCaptureExtension: Spring Boot Test에서 System.out, System.err, 로깅 출력 등을 캡처해서 테스트에서 검증할 수 있게 해주는 JUnit 5 Extension
// - OutputCaptureExtension: 테스트 실행 동안 콘솔 출력을 가로채서 저장
// - CapturedOutput: 캡처된 내용을 테스트 메서드에서 조회
class AopTest {

    @Autowired
    private OrderService orderService;

    @Test
    @DisplayName("OrderService 호출에 LoggingAspect가 적용된다")
    void 로깅_Aspect_적용_성공(CapturedOutput output) {
        // given
        assertThat(AopUtils.isAopProxy(orderService)).isTrue();

        // when
        orderService.orderItem("item-1");

        // then
        assertThat(output.getOut()).containsSubsequence(
                "before",
                "OrderService.orderItem() 실행됨",
                "after"
        );
    }
}
