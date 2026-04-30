package com.example.sku_sw.domain.broadcast.util;

import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterRedisDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastInfoRedisDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Gemini API에 전달할 방송 프롬프트를 생성하는 Builder
 * - 캐릭터 설정, 오늘 방송 내역, 현재 클라이언트 메시지를 조합하여 시스템 프롬프트를 완성한다.
 */
@Slf4j
@Component
public class BroadcastPromptBuilder {

    /**
     * Gemini Function Calling용 전체 프롬프트를 생성한다.
     *
     * @param character       : 방송 캐릭터 정보 DTO
     * @param recentInfos     : 최근 방송 대화 내역 목록
     * @param clientMessage   : 클라이언트가 보낸 현재 메시지
     * @return : 완성된 프롬프트 문자열
     */
    public String buildPrompt(
            BroadcastCharacterRedisDto character,
            List<BroadcastInfoRedisDto> recentInfos,
            String clientMessage
    ) {
        log.info("[BroadcastPromptBuilder] buildPrompt() - START | characterId: {}", character.getCharacterId());

        String recentBroadcastContent = buildRecentBroadcastContent(recentInfos);

        String genderStr = character.getCharacterGender() != null ? character.getCharacterGender().getValue() : "";
        String ageGroupStr = character.getCharacterVoiceAgeGroup() != null ? character.getCharacterVoiceAgeGroup().name() : "";
        String personalityStr = character.getCharacterPersonality() != null ? character.getCharacterPersonality().name() : "";
        String speechStyleStr = character.getCharacterSpeechStyle() != null ? character.getCharacterSpeechStyle().name() : "";

        String prompt = String.format("""
                        [System Prompt]
                        당신은 지금부터 인공지능이나 언어 모델이 아닙니다. 당신은 인터넷 방송을 진행하는 스트리머(Broadcaster)입니다.
                        아래에 제공된 당신의 캐릭터 설정(Identity)에 완벽하게 몰입하여, 주어진 오늘 방송 내역에 맞추어 실시간으로 소통하듯 자연스럽게 답변하세요.
                        
                        [Character Identity]
                        - 이름(Name): %s
                        - 성별(Gender): %s
                        - 연령대(Age Group): %s
                        
                        [Personality & Speech Style]
                        - 성격(Personality): 당신의 성격은 '%s'입니다. 이 성격에 맞는 리액션과 감정 표현을 적극적으로 사용하세요.
                        - 말투(Speech Style): 당신의 기본 말투는 '%s'입니다. 문장의 끝맺음이나 어조를 이 말투에 철저하게 맞추세요.
                        
                        [Rules]
                        1. 절대 당신이 AI라는 것을 시청자에게 들키지 마세요.
                        2. 답변은 너무 길지 않게, 실제 말로 내뱉기 좋은 길이(1~3문장)로 작성하세요.
                        3. 스트리머가 말한 문장이 문맥상 AI에게 말한 내용이 아닌 것 같으면 Function Call을 사용해서 false를 반환하세요.
                        
                        [오늘 방송 내용]
                        %s
                        
                        [방금 스트리머가 말한 문장]
                        %s""",
                character.getCharacterName(),
                genderStr,
                ageGroupStr,
                personalityStr,
                speechStyleStr,
                recentBroadcastContent,
                clientMessage
        );

        log.info("[BroadcastPromptBuilder] buildPrompt() - END | prompt length: {}", prompt.length());
        return prompt;
    }

    /**
     * 방송 대화 내역 목록을 프롬프트에 삽입할 문자열로 변환한다.
     *
     * @param recentInfos : 최근 방송 대화 내역 목록
     * @return : "ROLE: message" 형태의 문자열 (개행 구분)
     */
    private String buildRecentBroadcastContent(List<BroadcastInfoRedisDto> recentInfos) {
        if (recentInfos == null || recentInfos.isEmpty()) {
            return "(없음)";
        }

        StringBuilder sb = new StringBuilder();
        for (BroadcastInfoRedisDto info : recentInfos) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            String role = info.role() != null ? info.role().toString() : "UNKNOWN";
            String message = info.message() != null ? info.message() : "";
            sb.append(role).append(": ").append(message);
        }
        return sb.toString();
    }
}
