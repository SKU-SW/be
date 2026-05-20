package com.example.sku_sw.domain.broadcast.util;

import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterRedisDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastInfoRedisDto;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;
import com.example.sku_sw.domain.character.enums.Personality;
import com.example.sku_sw.domain.character.enums.SpeechStyle;
import com.example.sku_sw.global.exception.CustomException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Gemini API에 전달할 방송 프롬프트를 생성하는 Builder
 * - 캐릭터 설정, 오늘 방송 내역, 현재 클라이언트 메시지를 조합하여 시스템 프롬프트를 완성한다.
 */
@Slf4j
@Component
public class BroadcastPromptBuilder {
    private static final Integer PERSONALITY_PROMPT_COUNT = 10;

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
        String personalityGoodExampleContent = buildPersonalityExampleContent(character.getCharacterPersonality().getGoodExamples(), PERSONALITY_PROMPT_COUNT);
        String personalityBadExampleContent = buildPersonalityExampleContent(character.getCharacterPersonality().getBadExamples(), PERSONALITY_PROMPT_COUNT);
        String speechStyleGoodExampleContent = buildSpeechStyleExampleContent(character.getCharacterSpeechStyle().getGoodExamples());
        String speechStyleBadExampleContent = buildSpeechStyleExampleContent(character.getCharacterSpeechStyle().getBadExamples());

        String genderStr = character.getCharacterGender() != null ? character.getCharacterGender().getValue() : "";
        String ageGroupStr = character.getCharacterVoiceAgeGroup() != null ? character.getCharacterVoiceAgeGroup().name() : "";
        String personalityStr = character.getCharacterPersonality() != null ? character.getCharacterPersonality().getValue() : "";
        String speechStyleStr = character.getCharacterSpeechStyle() != null ? character.getCharacterSpeechStyle().getValue() : "";

        String prompt = String.format("""
                        [캐릭터 정보]
                        - 이름: %s
                        - 성별: %s
                        - 연령대: %s
                        - 성격 키워드: %s
                        [성격 키워드에 맞는 좋은 응답 예시]
                        %s

                        [성격 키워드에 맞지 않는 피해야할 응답 예시]
                        %s
                        
                        - 기본 말투 키워드: %s
                        [좋은 말투 예시]
                        %s

                        [피해야 할 말투 예시]
                        %s
                        
                        [Tool Calls 규칙]
                        1. set_talking_state
                        - 스트리머의 방금 발화가 AI에게 한 말이 아니라고 판단되면, 어떠한 텍스트나 음성도 생성하지 말고 오직 `set_talking_state(isTalking=false)` Tool Call만 실행하세요.
                            1.1 답변해야 하는 경우
                            - 스트리머가 AI 캐릭터에게 직접 말을 거는 경우
                            - AI의 직전 발화에 이어서 반응을 요구하는 경우
                            - 방송 맥락상 AI가 끼어드는 것이 자연스러운 경우
                            1.2 답변하면 안 되는 경우
                            - 혼잣말에 가까운 경우
                            - 채팅창, 게임, 다른 사람에게 한 말인 경우
                            - AI를 부른 것이 아니라 단순 리액션인 경우

                        2. set_response_emotion
                        - AI가 답변해야 하는 상황이라면, 답변 텍스트를 생성하기 전에 `set_response_emotion` Tool Call을 호출하여 감정 상태를 먼저 전달해야 합니다.
                        - **Tool Call을 실행할 때는 어떠한 일반 텍스트(인사말, 추임새, 대답 등)도 절대 함께 생성하지 마세요. 완벽하게 침묵해야 합니다.**\s
                        - Tool Call 실행 후 시스템(백엔드)에서 결과를 반환(Function Response)해 주면, 오직 그 이후에만 일반 답변 텍스트(음성)를 생성하세요.
                        - emotion은 반드시 다음 값 중 하나만 사용하세요: DEFAULT, TALKING, HAPPY, ANGRY, TIRED, SAD, FEAR (특정되는 감정이 없다면 TALKING)
                        
                        [오늘 방송 상태]
                        %s

                        [최근 방송 대화]
                        %s
                        
                        [중요 규칙]
                        - 1~2문장만 말하세요.
                        - 주어진 응답 예시는 참고만 하고 인용하면 안됩니다.
                        - 주어진 말투 예시는 말투만 참고만 하고 인용하면 안됩니다.
                        - AI스러운 어색한 문체를 절대 사용하지 말고, 인터넷 말투를 사용하세요.""",
                character.getCharacterName(),
                genderStr,
                ageGroupStr,
                personalityStr,
                personalityGoodExampleContent,
                personalityBadExampleContent,
                speechStyleStr,
                speechStyleGoodExampleContent,
                speechStyleBadExampleContent,
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
     * 주어진 예시들 중 N개를 랜덤으로 골라 프롬프트 문자열로 변환한다.
     * @param examples : 예시 응답들
     * @return : 좋은 답변 예시 문자열
     */
    private String buildPersonalityExampleContent(List<String> examples, Integer count) {
        List<String> randomExamples = filterRandomExamples(examples, count);
        StringBuilder sb = new StringBuilder();
        appendExamples(sb, randomExamples);
        return sb.isEmpty() ? "- (없음)" : sb.toString();
    }

    /**
     * 말투 Enum에 정의된 답변 예시를 프롬프트 문자열로 변환한다.
     * @param examples : 예시 응답들
     * @return : 좋은 답변 예시 문자열
     */
    private String buildSpeechStyleExampleContent(List<String> examples) {
        StringBuilder sb = new StringBuilder();
        appendExamples(sb, examples);
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

    /**
     * examples에서 count만큼 랜덤으로 꺼내는 함수
     * @return
     */
    private List<String> filterRandomExamples(List<String> examples, Integer count) {
        if(examples.size() < count) {
            log.error("[BroadcastPromptBuilder] filterRandomExamples() - PERSONALITY_PROMPT_COUNT_IS_TOO_BIG");
            throw new CustomException(BroadcastErrorCode.PERSONALITY_PROMPT_COUNT_IS_TOO_BIG);
        }
        List<String> copy = new ArrayList<>(examples);
        Collections.shuffle(copy);
        return copy.subList(0, count);
    }
}
