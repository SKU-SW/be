package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.broadcast.dto.BroadcastInfoRedisDto;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class BroadcastDialogueSummaryPromptBuilder {

    /**
     * 방송 summary 갱신용 프롬프트를 생성하는 함수
     * @param currentSummary : 현재 summary DTO
     * @param dialogues : 새로 반영할 대화 목록
     * @return : summary 생성 프롬프트
     */
    public String buildPrompt(BroadcastInfoRedisDto currentSummary, List<BroadcastInfoRedisDto> dialogues) {
        StringBuilder dialogueSection = new StringBuilder();

        for (BroadcastInfoRedisDto dialogue : dialogues) {
            if (dialogueSection.length() > 0) {
                dialogueSection.append("\n");
            }
            dialogueSection.append(dialogue.subject()).append(": ").append(dialogue.content());
        }

        String currentSummaryContent = currentSummary != null && currentSummary.content() != null
                ? currentSummary.content()
                : "(오늘 방송 요약 없음)";

        return String.format("""
                [역할]
                당신은 방송 대화 기록을 압축해서 '오늘 방송 흐름 요약'을 갱신하는 요약 모델입니다.

                [기존 오늘 방송 요약]
                %s

                [이번에 새롭게 반영할 방송 대화]
                %s

                [지시사항]
                1. 기존 요약과 새 대화를 합쳐 오늘 방송 흐름을 자연스럽게 누적 요약하세요.
                2. 방송 진행 흐름, 주요 화제, 분위기 변화 위주로 4~6문장으로 요약하세요.
                3. 불필요한 메타 설명 없이 요약문 본문만 출력하세요.
                """, currentSummaryContent, dialogueSection.length() > 0 ? dialogueSection : "(없음)");
    }
}
