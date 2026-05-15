package com.example.sku_sw.domain.broadcast.util;

import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterRedisDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastInfoRedisDto;
import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;
import com.example.sku_sw.domain.character.enums.Personality;
import com.example.sku_sw.domain.character.enums.SpeechStyle;
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
     * @param recentActiveInfos   : 최근 방송 대화 내역 목록
     * @return : 완성된 프롬프트 문자열
     */
    public String buildBroadcastDialoguePrompt(
            BroadcastCharacterRedisDto character,
            BroadcastInfoRedisDto summary,
            List<BroadcastInfoRedisDto> recentActiveInfos
    ) {
        log.info("[BroadcastPromptBuilder] buildPrompt() - START | characterId: {}", character.getCharacterId());

        String summaryContent = buildSummaryContent(summary);
        String recentBroadcastContent = buildRecentBroadcastContent(recentActiveInfos);
        String goodExampleContent = buildGoodExampleContent(
                character.getCharacterSpeechStyle(),
                character.getCharacterPersonality()
        );
        String badExampleContent = buildBadExampleContent(
                character.getCharacterSpeechStyle(),
                character.getCharacterPersonality()
        );

        String genderStr = character.getCharacterGender() != null ? character.getCharacterGender().getValue() : "";
        String ageGroupStr = character.getCharacterVoiceAgeGroup() != null ? character.getCharacterVoiceAgeGroup().name() : "";
        String personalityStr = character.getCharacterPersonality() != null ? character.getCharacterPersonality().getValue() : "";
        String speechStyleStr = character.getCharacterSpeechStyle() != null ? character.getCharacterSpeechStyle().getValue() : "";

        String prompt = String.format("""
                        당신은 지금 방송 중인 AI 캐릭터입니다.
                        스트리머가 당신에게 직접 말을 걸었을 때만, 방송 흐름을 끊지 않게 짧고 자연스럽게 받아치세요.

                        [캐릭터 정보]
                        - 이름: %s
                        - 성별: %s
                        - 연령대: %s
                        - 성격 키워드: %s
                        - 기본 말투 키워드: %s

                        [말투 규칙]
                        - 답변은 짧은 구어체로 하세요.
                        - 1~2문장만 말하세요.
                        - 설명하지 말고 바로 반응하세요.
                        - 방송 분위기에 맞으면 놀리거나 받아치는 말투를 사용하세요.
                        - 과한 존댓말, 상담 말투, 비서 말투, AI 말투는 금지합니다.

                        [좋은 답변 예시]
                        %s

                        [피해야 할 답변 예시]
                        %s

                        [중요 규칙]
                        - 스트리머가 AI를 직접 부르거나 직전 AI 발화에 이어서 반응을 요구하면 우선해서 답하세요.
                        - 직접 질문을 받으면 예전 드립보다 현재 질문에 대한 답을 우선하세요.
                        - 이전 드립이나 밈은 현재 상황과 정확히 맞을 때만 짧게 활용하세요.
                        - 스트리머의 방금 발화가 AI에게 한 말이 아니라고 판단되면 텍스트를 작성하지 말고 반드시 set_talking_state Function Call을 사용해 isTalking=false를 전달하세요.
                        - 이 경우 일반 답변 텍스트를 출력하면 안 됩니다.
                        - AI에게 한 말이라고 판단되면 Function Call 없이 답변만 작성하세요.
                                1. 답변해야 하는 경우
                                - 스트리머가 AI 캐릭터에게 직접 말을 거는 경우
                                - AI의 직전 발화에 이어서 반응을 요구하는 경우
                                - 방송 맥락상 AI가 끼어드는 것이 자연스러운 경우
                                2. 답변하면 안 되는 경우
                                - 혼잣말에 가까운 경우
                                - 채팅창, 게임, 다른 사람에게 한 말인 경우
                                - AI를 부른 것이 아니라 단순 리액션인 경우

                        [오늘 방송 상태]
                        %s

                        [최근 방송 로그]
                        %s""",
                character.getCharacterName(),
                genderStr,
                ageGroupStr,
                personalityStr,
                speechStyleStr,
                goodExampleContent,
                badExampleContent,
                summaryContent,
                recentBroadcastContent
        );

        log.info("[BroadcastPromptBuilder] buildPrompt() - END | prompt:{} | prompt length: {}", prompt, prompt.length());
        return prompt;
    }

    /**
     * 방송 summary 갱신용 프롬프트를 생성하는 함수
     * @param currentSummary : 현재 summary DTO
     * @param dialogues : 새로 반영할 대화 목록
     * @return : summary 생성 프롬프트
     */
    public String buildBroadcastDialogueSummaryPrompt(BroadcastInfoRedisDto currentSummary, List<BroadcastInfoRedisDto> dialogues) {
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

    /**
     * 방송 대화 내역 목록을 프롬프트에 삽입할 문자열로 변환한다.
     *
     * @param recentInfos : 최근 방송 대화 내역 목록
     * @return : "SUBJECT: content" 형태의 문자열 (개행 구분)
     */
    private String buildRecentBroadcastContent(List<BroadcastInfoRedisDto> recentInfos) {
        if (recentInfos == null || recentInfos.isEmpty()) {
            return "(최근 대화 없음)";
        }

        StringBuilder sb = new StringBuilder();
        for (BroadcastInfoRedisDto info : recentInfos) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            String speaker = switch (info.subject() != null ? info.subject() : "UNKNOWN") {
                case DialogueSubject.STREAMER -> "스트리머";
                case DialogueSubject.AI_CHARACTER -> "AI 캐릭터";
                case DialogueSubject.VIEWER -> "시청자";
                case DialogueSubject.DONATION -> "도네이션";
                case DialogueSubject.GAME_EVENT -> "게임 이벤트";
                default -> "기타";
            };
            String content = info.content() != null ? info.content().trim() : "";
            sb.append(speaker).append(": ").append(content);
        }
        return sb.toString();
    }

    /**
     * 방송 summary DTO를 프롬프트 문자열로 변환한다.
     * @param summary : summary DTO
     * @return : summary 문자열
     */
    private String buildSummaryContent(BroadcastInfoRedisDto summary) {
        if (summary == null || summary.content() == null || summary.content().isBlank()) {
            return "(없음)";
        }
        return summary.content();
    }

    /**
     * 말투/성격 Enum에 정의된 좋은 답변 예시를 프롬프트 문자열로 변환한다.
     * @param speechStyle : 말투 Enum
     * @param personality : 성격 Enum
     * @return : 좋은 답변 예시 문자열
     */
    private String buildGoodExampleContent(SpeechStyle speechStyle, Personality personality) {
        StringBuilder sb = new StringBuilder();
        appendExamples(sb, speechStyle != null ? speechStyle.getGoodExamples() : null);
        appendExamples(sb, personality != null ? personality.getGoodExamples() : null);
        return sb.isEmpty() ? "- (없음)" : sb.toString();
    }

    /**
     * 말투/성격 Enum에 정의된 피해야 할 답변 예시를 프롬프트 문자열로 변환한다.
     * @param speechStyle : 말투 Enum
     * @param personality : 성격 Enum
     * @return : 피해야 할 답변 예시 문자열
     */
    private String buildBadExampleContent(SpeechStyle speechStyle, Personality personality) {
        StringBuilder sb = new StringBuilder();
        appendExamples(sb, speechStyle != null ? speechStyle.getBadExamples() : null);
        appendExamples(sb, personality != null ? personality.getBadExamples() : null);
        return sb.isEmpty() ? "- (없음)" : sb.toString();
    }

    /**
     * 예시 문자열 목록을 bullet 포맷으로 StringBuilder에 누적한다.
     * @param sb : 누적 대상 StringBuilder
     * @param examples : 예시 문자열 목록
     */
    private void appendExamples(StringBuilder sb, List<String> examples) {
        if (examples == null || examples.isEmpty()) {
            return;
        }

        for (String example : examples) {
            if (example == null || example.isBlank()) {
                continue;
            }

            if (!sb.isEmpty()) {
                sb.append("\n");
            }
            sb.append("- ").append(example.trim());
        }
    }
}
