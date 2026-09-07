package com.example.sku_sw.global.aop;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    /**
     * 상품 주문을 처리한다.
     * - Service에서 Repository로 이어지는 호출에 AOP가 적용되는 과정을 확인하기 위한 메서드다.
     *
     * @param itemId 주문할 상품 ID
     */
    @Transactional
    public void orderItem(String itemId) {
        System.out.println("OrderService.orderItem() 실행됨");
    }
}
