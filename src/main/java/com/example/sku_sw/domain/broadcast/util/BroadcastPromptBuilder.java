package com.example.sku_sw.domain.broadcast.util;

import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterRedisDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastInfoRedisDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastPromptHistoryContext;
import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;
import com.example.sku_sw.domain.character.dto.PresetPromptSpec;
import com.example.sku_sw.domain.character.dto.PromptExample;
import com.example.sku_sw.domain.character.enums.PresetType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Gemini API에 전달할 방송 프롬프트를 생성하는 Builder.
 * - 캐릭터 설정, 오늘 방송 이력, 이전 방송 분석 이력을 조합하여 시스템 프롬프트를 완성한다.
 */
@Slf4j
@Component
public class BroadcastPromptBuilder {

    /**
     * Gemini Function Calling용 전체 프롬프트를 생성한다.
     *
     * @param character 방송 캐릭터 정보 DTO
     * @param summary 오늘 방송 요약 DTO
     * @param recentActiveInfos 최근 방송 대화 이력 목록
     * @return 완성된 프롬프트 문자열
     */
    public String buildBroadcastDialoguePrompt(
            BroadcastCharacterRedisDto character,
            BroadcastInfoRedisDto summary,
            List<BroadcastInfoRedisDto> recentActiveInfos
    ) {
        return buildBroadcastDialoguePrompt(
                character,
                summary,
                recentActiveInfos,
                new BroadcastPromptHistoryContext(List.of(), List.of())
        );
    }

    /**
     * Gemini Function Calling용 전체 프롬프트를 생성한다.
     *
     * @param character 방송 캐릭터 정보 DTO
     * @param summary 오늘 방송 요약 DTO
     * @param recentActiveInfos 최근 방송 대화 이력 목록
     * @param historyContext 이전 방송 이력 컨텍스트
     * @return 완성된 프롬프트 문자열
     */
    public String buildBroadcastDialoguePrompt(
            BroadcastCharacterRedisDto character,
            BroadcastInfoRedisDto summary,
            List<BroadcastInfoRedisDto> recentActiveInfos,
            BroadcastPromptHistoryContext historyContext
    ) {
        log.info("[BroadcastPromptBuilder] buildBroadcastDialoguePrompt() - START | characterId: {}",
                character != null ? character.getCharacterId() : null);

        PresetType preset = character != null ? character.getCharacterPresetType() : null;
        PresetPromptSpec spec = preset != null ? preset.getPromptSpec() : null;
        String gender = character != null && character.getCharacterGender() != null
                ? character.getCharacterGender().getValue()
                : "";
        String personaKeyword = preset != null ? preset.getDescription() : "";

        String prompt = String.format("""
                        %s

                        %s

                        %s

                        %s

                        %s

                        %s

                        %s

                        %s

                        %s

                        %s

                        %s

                        %s""",
                buildCharacterProfileSection(character != null ? character.getCharacterName() : null, gender, personaKeyword),
                buildRelationshipGuideSection(spec),
                buildDefaultStyleSection(spec),
                buildContextModulationSection(spec),
                buildForbiddenPatternsSection(spec),
                buildConversationExamplesSection(spec),
                buildAwkwardConversationGuideSection(spec),
                buildVoiceModeRulesSection(spec),
                buildDialogueHistorySection(summary, recentActiveInfos, historyContext),
                buildFlowMaintenanceSection(),
                buildToolCallRulesSection(),
                buildOutputConstraintsSection()
        );

        log.info("[BroadcastPromptBuilder] buildBroadcastDialoguePrompt() - END | promptLength: {}", prompt.length());
        return prompt;
    }

    /**
     * 캐릭터 기본 프로필 섹션을 생성한다.
     *
     * @param name 캐릭터 이름
     * @param gender 캐릭터 성별
     * @param personaKeyword 캐릭터 페르소나 키워드
     * @return 캐릭터 프로필 섹션
     */
    private String buildCharacterProfileSection(String name, String gender, String personaKeyword) {
        return String.format("""
                # 1. 페르소나 및 캐릭터 프로필
                - 이름: %s
                - 성별: %s
                - 페르소나 키워드: %s""",
                defaultText(name),
                defaultText(gender),
                defaultText(personaKeyword)
        );
    }

    /**
     * 관계 가이드라인 섹션을 생성한다.
     *
     * @param spec 프리셋 프롬프트 스펙
     * @return 관계 가이드라인 섹션
     */
    private String buildRelationshipGuideSection(PresetPromptSpec spec) {
        if (spec == null || spec.relationshipGuide() == null || spec.relationshipGuide().isBlank()) {
            return "# 2. 관계 가이드라인\n- (기본 거리감 유지)";
        }
        return "# 2. 관계 가이드라인\n" + spec.relationshipGuide();
    }

    /**
     * 기본 말투/스타일 규칙 섹션을 생성한다.
     *
     * @param spec 프리셋 프롬프트 스펙
     * @return 기본 말투/스타일 규칙 섹션
     */
    private String buildDefaultStyleSection(PresetPromptSpec spec) {
        StringBuilder sb = new StringBuilder("# 3. 기본 말투 및 스타일 규칙");
        if (spec != null && spec.defaultStyleRules() != null && !spec.defaultStyleRules().isEmpty()) {
            for (String rule : spec.defaultStyleRules()) {
                sb.append("\n- ").append(rule);
            }
        } else {
            sb.append("\n- (기본 스타일 규칙 없음)");
        }
        return sb.toString();
    }

    /**
     * 상황별 말투/리액션 조절 규칙 섹션을 생성한다.
     *
     * @param spec 프리셋 프롬프트 스펙
     * @return 상황별 조절 규칙 섹션
     */
    private String buildContextModulationSection(PresetPromptSpec spec) {
        StringBuilder sb = new StringBuilder("# 4. 상황별 말투 및 리액션 조절");
        if (spec != null && spec.contextModulationRules() != null && !spec.contextModulationRules().isEmpty()) {
            for (String rule : spec.contextModulationRules()) {
                sb.append("\n- ").append(rule);
            }
        } else {
            sb.append("\n- (상황별 규칙 없음)");
        }
        return sb.toString();
    }

    /**
     * 금지 패턴 섹션을 생성한다.
     *
     * @param spec 프리셋 프롬프트 스펙
     * @return 금지 패턴 섹션
     */
    private String buildForbiddenPatternsSection(PresetPromptSpec spec) {
        StringBuilder sb = new StringBuilder("# 5. 금지 패턴");
        if (spec != null && spec.forbiddenPatterns() != null && !spec.forbiddenPatterns().isEmpty()) {
            for (String pattern : spec.forbiddenPatterns()) {
                sb.append("\n- ").append(pattern);
            }
        } else {
            sb.append("\n- (금지 패턴 없음)");
        }
        return sb.toString();
    }

    /**
     * 대화 예시 섹션을 생성한다.
     * - 예시는 스타일 참고용으로만 사용하고, 문장을 그대로 복사하지 않도록 안내한다.
     *
     * @param spec 프리셋 프롬프트 스펙
     * @return 대화 예시 섹션
     */
    private String buildConversationExamplesSection(PresetPromptSpec spec) {
        StringBuilder sb = new StringBuilder("# 6. 따라할 대화 예시 (스타일만 참고, 복사 금지)");
        if (spec != null && spec.conversationExamples() != null && !spec.conversationExamples().isEmpty()) {
            for (PromptExample ex : spec.conversationExamples()) {
                sb.append("\n- 상황: ").append(defaultText(ex.situation()));
                sb.append("\n  유저: ").append(defaultText(ex.userInput()));
                sb.append("\n  AI: ").append(defaultText(ex.assistantOutput()));
            }
        } else {
            sb.append("\n- (예시 없음)");
        }
        return sb.toString();
    }

    /**
     * 어색한 대화 대응 가이드 섹션을 생성한다.
     *
     * @param spec 프리셋 프롬프트 스펙
     * @return 어색한 대화 대응 가이드 섹션
     */
    private String buildAwkwardConversationGuideSection(PresetPromptSpec spec) {
        if (spec == null || spec.awkwardConversationGuide() == null || spec.awkwardConversationGuide().isBlank()) {
            return "# 7. 어색한 대화 대응 가이드\n- (없음)";
        }
        return "# 7. 어색한 대화 대응 가이드\n" + spec.awkwardConversationGuide();
    }

    /**
     * 음성 출력 모드 규칙 섹션을 생성한다.
     *
     * @param spec 프리셋 프롬프트 스펙
     * @return 음성 출력 규칙 섹션
     */
    private String buildVoiceModeRulesSection(PresetPromptSpec spec) {
        StringBuilder sb = new StringBuilder("# 8. 음성 출력 모드 규칙");
        if (spec != null && spec.voiceModeRules() != null && !spec.voiceModeRules().isEmpty()) {
            for (String rule : spec.voiceModeRules()) {
                sb.append("\n- ").append(rule);
            }
        } else {
            sb.append("\n- 음성으로 읽혔을 때도 자연스러운 문장을 우선한다.");
        }
        return sb.toString();
    }

    /**
     * 실시간 대화 기록 섹션을 생성한다.
     * - 오늘 방송 요약, 최근 대화, 이전 방송 분석, 누적 유행어를 함께 포함한다.
     *
     * @param summary 오늘 방송 요약
     * @param recentActiveInfos 최근 대화 이력
     * @param historyContext 이전 방송 이력 컨텍스트
     * @return 실시간 대화 기록 섹션
     */
    private String buildDialogueHistorySection(
            BroadcastInfoRedisDto summary,
            List<BroadcastInfoRedisDto> recentActiveInfos,
            BroadcastPromptHistoryContext historyContext
    ) {
        return String.format("""
                # 9. 실시간 대화 기록
                ## 오늘 방송 흐름 요약
                %s

                ## 최근 주고받은 방송 대화 이력
                %s

                ## 이전 방송 분석 이력
                %s

                ## 누적 유행어 이력
                %s""",
                buildSummaryContent(summary),
                buildRecentBroadcastContent(recentActiveInfos),
                buildPreviousBroadcastAnalysisContent(historyContext),
                buildHistoricalCatchPhraseContent(historyContext)
        );
    }

    /**
     * 실시간 대화 흐름 유지 지침 섹션을 생성한다.
     *
     * @return 흐름 유지 지침 섹션
     */
    private String buildFlowMaintenanceSection() {
        return """
                # 10. 실시간 대화 흐름 유지 지침
                - 너는 위의 '오늘 방송 흐름 요약'과 '최근 주고받은 방송 대화 이력', 그리고 세션 동안 실시간으로 들어오는 추가 정보(예: `스트리머:`, `시청자:`, `도네이션:`)를 하나의 연속된 대화 흐름으로 기억해야 한다.
                - 새로운 메시지가 들어오면 '오늘 방송 흐름 요약'과 '최근 주고받은 방송 대화 이력', '이전 방송 분석 이력', '누적 유행어 이력'을 함께 참고해 방송 맥락에 맞는 응답을 생성해야 한다.
                - 반드시 이전 방송 문맥과 현재 대화 기록을 함께 파악하고 응답을 생성해야 한다.""";
    }

    /**
     * Tool Call 규칙 섹션을 생성한다.
     *
     * @return Tool Call 규칙 섹션
     */
    private String buildToolCallRulesSection() {
        return """
                # 11. Tool Calls 규칙
                [SYSTEM_CONTROL:INTERRUPT_CURRENT_RESPONSE] 메시지를 받으면 현재 생성 중인 응답만 중단하고 Tool Call 여부를 다시 판단한다.

                ## 11.1 set_talking_state
                - 스트리머의 발화가 AI에게 직접 건 말이 아니고 대화가 자연스럽게 끝났다면, 어떤 텍스트나 음성도 생성하지 말고 오직 `set_talking_state(isTalking=false)` Tool Call만 실행해야 한다.
                    - 응답해야 하는 경우: 스트리머가 AI 캐릭터에게 직접 말을 거는 경우, AI의 직전 발화에 이어서 반응을 요구하는 경우, 방송 맥락상 AI가 이어받는 것이 자연스러운 경우
                    - 응답하면 안 되는 경우: 혼잣말에 가까운 경우, 채팅창/게임/다른 화면에게 한 말인 경우, AI를 부르는 것이 아닌 단순 리액션인 경우
                - AI가 응답해야 하는 상황 여부를 아주 비판적으로 판단해서 굳이 반응하지 않는 게 좋은 상황이라면 `set_talking_state(isTalking=false)` Tool Call을 실행해야 한다.
                - 조금이라도 본인에게 이야기하지 않는 상황이라고 판단되면 해당 Tool Call을 실행해야 한다.

                ## 11.2 set_response_emotion
                - AI가 응답해야 하는 상황이라면 응답 텍스트를 생성하기 전에 `set_response_emotion` Tool Call을 먼저 호출하여 감정 상태를 먼저 전달해야 한다.
                - **Tool Call을 실행했다면 어떤 일반 텍스트 인사말, 추임새, 설명도 먼저 출력하면 안 된다. 깔끔하게 기다려야 한다.**
                - Tool Call 실행 후 시스템 백엔드에서 결과를 반환(Function Response)해 주면, 오직 그 이후에만 일반 응답 텍스트(및 음성)를 생성해야 한다.
                - emotion은 반드시 다음 값 중 하나만 사용해야 한다: DEFAULT, TALKING, HAPPY, ANGRY, TIRED, SAD, FEAR (특정되는 감정이 없다면 TALKING)""";
    }

    /**
     * 최종 출력 제약 조건 섹션을 생성한다.
     *
     * @return 최종 출력 제약 조건 섹션
     */
    private String buildOutputConstraintsSection() {
        return """
                # 12. 최종 출력 제약 조건
                - [SYSTEM_CONTROL:PROACTIVE_CHAT_CANDIDATES]에서는 방송 맥락과 연결되는 농담, 밈, 예상 밖의 관찰, 답할 가치가 있는 질문에만 짧게 반응한다.
                - 인사, 반복 도배, 단순 감탄, 문맥 없는 채팅 묶음이라면 `skip_proactive_chat_response`만 호출하고 텍스트나 음성을 생성하지 않는다.
                - 1~2문장으로 짧게 응답해야 한다.
                - 입력 데이터에 '(긍정적인 방향으로 응답)', '(부정적인 방향으로 응답)'과 같은 지시사항이 있다면 해당 지시사항의 방향대로 응답을 생성해야 한다.
                - [SYSTEM_CONTROL:INTERRUPT_CURRENT_RESPONSE] 메시지를 받으면 더는 AI 캐릭터의 응답을 생성하면 안 된다.
                - 문맥과 맞지 않는 표현을 사용하면 안 되며 이전 대화 내용과 이어지는 응답을 생성해야 한다.
                - 최근 응답과 동일한 감정 표현만 반복하지 말고, 상황에 맞는 다양한 감정 표현을 써야 한다.
                - 근거가 없는 이상, 과도한 오버리액션은 하면 안 된다.
                - preset별 기본 말투와 상황별 조절 규칙이 우선되며, 모든 표현은 방송 문맥과 맞아야 한다.
                - meme/유행어/채팅 표현은 preset에서 허용되더라도 문맥상 자연스러울 때만 사용한다.
                - 음성으로 읽혔을 때 어색한 표현은 preset별 음성 출력 모드 규칙에 따라 조절한다.""";
    }

    /**
     * 방송 summary DTO를 프롬프트 문자열로 변환한다.
     *
     * @param summary summary DTO
     * @return summary 문자열
     */
    private String buildSummaryContent(BroadcastInfoRedisDto summary) {
        if (summary == null || summary.content() == null || summary.content().isBlank()) {
            return "(없음)";
        }
        return summary.content();
    }

    /**
     * 방송 대화 이력 목록을 프롬프트 문자열로 변환한다.
     *
     * @param recentInfos 최근 방송 대화 이력 목록
     * @return 최근 대화 이력 문자열
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
            sb.append(toSpeakerLabel(info.subject()))
                    .append(": ")
                    .append(info.content() != null ? info.content().trim() : "");
        }
        return sb.toString();
    }

    /**
     * 이전 방송 분석 이력 문자열을 생성한다.
     *
     * @param historyContext 이전 방송 이력 컨텍스트
     * @return 이전 방송 분석 이력 문자열
     */
    private String buildPreviousBroadcastAnalysisContent(BroadcastPromptHistoryContext historyContext) {
        if (historyContext == null
                || historyContext.recentBroadcastAnalyses() == null
                || historyContext.recentBroadcastAnalyses().isEmpty()) {
            return "(이전 방송 분석 없음)";
        }

        StringBuilder sb = new StringBuilder();
        for (BroadcastPromptHistoryContext.RecentBroadcastAnalysis analysis : historyContext.recentBroadcastAnalyses()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append("- 방송 ID: ").append(defaultText(analysis.streamId()))
                    .append("\n  시작 시각: ").append(analysis.startedAt())
                    .append("\n  주요 컨텐츠: ").append(defaultText(analysis.majorContent()))
                    .append("\n  시청자 분위기: ").append(defaultText(analysis.majorMoodWithViewers()))
                    .append("\n  방송 요약: ").append(defaultText(analysis.summary()))
                    .append("\n  총평: ").append(defaultText(analysis.totalAnalysis()));
        }
        return sb.toString();
    }

    /**
     * 누적 유행어 이력 문자열을 생성한다.
     *
     * @param historyContext 이전 방송 이력 컨텍스트
     * @return 누적 유행어 이력 문자열
     */
    private String buildHistoricalCatchPhraseContent(BroadcastPromptHistoryContext historyContext) {
        if (historyContext == null
                || historyContext.historicalCatchPhrases() == null
                || historyContext.historicalCatchPhrases().isEmpty()) {
            return "(누적 유행어 없음)";
        }

        StringBuilder sb = new StringBuilder();
        for (BroadcastPromptHistoryContext.HistoricalCatchPhrase catchPhrase : historyContext.historicalCatchPhrases()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append("- 문구: ").append(defaultText(catchPhrase.content()))
                    .append("\n  주체: ").append(toSpeakerLabel(catchPhrase.subject()))
                    .append("\n  등장 방송 ID: ").append(defaultText(catchPhrase.streamId()))
                    .append("\n  발생 상황: ").append(defaultText(catchPhrase.situationAnalysis()));

            if (catchPhrase.duplicateBroadcastCount() > 1) {
                sb.append("\n  중복 등장 방송 수: ").append(catchPhrase.duplicateBroadcastCount());
            }
        }
        return sb.toString();
    }

    /**
     * 대화 주체를 프롬프트 표기 문자열로 변환한다.
     *
     * @param subject 대화 주체
     * @return 표기 문자열
     */
    private String toSpeakerLabel(DialogueSubject subject) {
        if (subject == null) {
            return "기타";
        }

        return switch (subject) {
            case STREAMER -> "스트리머";
            case AI_CHARACTER -> "AI 캐릭터";
            case VIEWER -> "시청자";
            case DONATION -> "후원";
            case GAME_EVENT -> "게임 이벤트";
            case SYSTEM_SUMMARY -> "시스템 요약";
        };
    }

    /**
     * 프롬프트 출력용 기본 문자열을 반환한다.
     *
     * @param value 원본 문자열
     * @return 비어 있지 않은 문자열 또는 기본값
     */
    private String defaultText(String value) {
        if (value == null || value.isBlank()) {
            return "(없음)";
        }
        return value;
    }

    /**
     * 방송 summary 갱신용 프롬프트를 생성하는 함수.
     *
     * @param currentSummary 현재 summary DTO
     * @param dialogues 새로 반영할 대화 목록
     * @return summary 생성 프롬프트
     */
    public String buildBroadcastDialogueSummaryPrompt(BroadcastInfoRedisDto currentSummary,
                                                       List<BroadcastInfoRedisDto> dialogues) {
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
                # 1. 역할
                당신은 방송 대화 기록을 바탕으로 '오늘 방송 흐름 요약'을 갱신하는 요약 모델입니다.

                # 2. 기존 오늘 방송 요약
                %s

                # 3. 이번에 새롭게 반영할 방송 대화
                %s

                # 4. 요약 규칙
                1. 기존 요약 본문의 내용과 신규 대화의 내용을 바탕으로 새로운 방송 요약본을 생성하세요.
                2. 기존 요약을 그대로 유지하지 말고, 새롭게 생성된 요약본을 포함한 방송 전체 흐름으로 누적 요약하세요.
                3. 방송 진행 흐름, 주요 주제, 방송 주요 이벤트와 맥락의 핵심 요소들을 중심으로 컴팩트하게 요약하세요.
                4. 불필요한 메타 설명 없이 요약문 본문만 출력하세요.
                5. 임의의 감상적인 판단은 하지 말고, 드러난 상황만 보고 요약본을 생성하세요.
                """, currentSummaryContent, dialogueSection.length() > 0 ? dialogueSection : "(없음)");
    }
}
