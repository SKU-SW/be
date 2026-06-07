package com.example.sku_sw.domain.character.dto;

import java.util.List;

/**
 * 프리셋 타입별 프롬프트 명세를 담는 데이터 구조.
 * <p>
 * 이 레코드는 {@link com.example.sku_sw.domain.character.enums.PresetType PresetType} 각 상수가
 * Gemini 시스템 프롬프트를 구성하는 데 필요한 모든 지침과 예시를 구조화하여 보관한다.
 * API 요청/응답 DTO가 아니라 순수한 프롬프트 구성 자료구조이다.
 * </p>
 *
 * @param relationshipGuide        캐릭터와 스트리머 간의 관계/거리감 가이드라인
 * @param defaultStyleRules        기본 말투, 길이, 텐션 등에 대한 규칙 목록
 * @param contextModulationRules   상황별 말투/텐션 조절 규칙 목록
 * @param forbiddenPatterns        생성해서는 안 되는 금지 패턴 목록
 * @param conversationExamples     (userInput → assistantOutput) 대화 예시 목록
 * @param voiceModeRules           음성 출력 시 준수해야 할 규칙 목록
 * @param awkwardConversationGuide 피해야 할 대화 예시 원문 (raw text block)
 */
public record PresetPromptSpec(
        String relationshipGuide,
        List<String> defaultStyleRules,
        List<String> contextModulationRules,
        List<String> forbiddenPatterns,
        List<PromptExample> conversationExamples,
        List<String> voiceModeRules,
        String awkwardConversationGuide
) {
}
