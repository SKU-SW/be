package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.broadcast.dto.BroadcastInfoRedisDto;
import com.example.sku_sw.global.util.GeminiUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastDialogueSummaryService {

    private final GeminiUtil geminiUtil;
    private final BroadcastDialogueSummaryPromptBuilder promptBuilder;

    /**
     * 방송 summary를 비동기로 생성하는 함수
     * @param currentSummary : 현재 summary DTO
     * @param dialogues : 새로 요약에 반영할 대화 목록
     * @return : 새 summary text Mono
     */
    public Mono<String> summarize(BroadcastInfoRedisDto currentSummary, List<BroadcastInfoRedisDto> dialogues) {
        log.info("[BroadcastDialogueSummaryService] summarize() - START | dialogueSize: {}", dialogues.size());

        /*
            1. 요약 프롬프트 생성
            - 기존 summary와 이번 batch 대화를 합쳐 요약용 프롬프트를 생성한다.
         */
        String prompt = promptBuilder.buildPrompt(currentSummary, dialogues);

        /*
            2. Summary AI 호출
            - 별도 summary 전용 모델을 호출하여 오늘 방송 흐름 요약을 갱신한다.
         */
        Mono<String> result = geminiUtil.summarizeDialogues(prompt)
                .doOnSuccess(summary -> log.info("[BroadcastDialogueSummaryService] summarize() - END | summaryLength: {}",
                        summary != null ? summary.length() : 0))
                .doOnError(error -> log.error("[BroadcastDialogueSummaryService] summarize() - Failed | error: {}", error.getMessage()));

        return result;
    }
}
