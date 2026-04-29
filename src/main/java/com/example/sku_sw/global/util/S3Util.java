package com.example.sku_sw.global.util;

import com.example.sku_sw.domain.character.entity.CharacterImage;
import com.example.sku_sw.domain.character.entity.CharacterImageDetail;
import com.example.sku_sw.domain.character.enums.Emotion;
import com.example.sku_sw.domain.character.enums.Gender;
import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * S3 Util 클래스
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class S3Util {
    @Value("${spring.cloud.cloudfront.domain}")
    private String cloudFrontDomain;

    /**
     * DB 저장용 S3 이미지 엔드포인트 생성 함수
     * @param gender 캐릭터 이미지 성별
     * @param preset 캐릭터 프리셋 이름
     * @param emotion 캐릭터 감정
     * @return 해당 캐릭터 S3 엔드포인트 경로
     */
    public String createCharacterImageEndpoint(Gender gender, String preset, Emotion emotion) {
        return "/character/" + gender.getValue() + "/" + preset + "/images/" + emotion.getValue() + ".jpg";
    }

    /**
     * 실제 접근 가능한 캐릭터 이미지 url 생성 함수
     * @param imageUrl 캐릭터 이미지 엔드포인트
     * @return 실제 접근 가능 캐릭터 이미지 url
     */
    public String createFullCharacterImageUrl(String imageUrl){
        return cloudFrontDomain + imageUrl;
    }

}
