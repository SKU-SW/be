package com.example.sku_sw.domain.character.dto;

/**
 * 프롬프트 조합에 사용되는 대화 예시 데이터 구조.
 * <p>
 * 이 레코드는 Gemini 시스템 프롬프트 내에 주입할 (userInput → assistantOutput) 예시 쌍을 나타낸다.
 * API 요청/응답 DTO가 아니라 순수한 프롬프트 구성 자료구조이다.
 * </p>
 *
 * @param userInput       스트리머/시청자의 발화 예시
 * @param assistantOutput AI 캐릭터의 바람직한 응답 예시
 * @param situation       해당 예시가 적용되는 상황 설명 (선택적 맥락)
 */
public record PromptExample(
        String userInput,
        String assistantOutput,
        String situation
) {
}
