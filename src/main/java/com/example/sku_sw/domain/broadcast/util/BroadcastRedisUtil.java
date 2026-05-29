package com.example.sku_sw.domain.broadcast.util;

import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterRedisDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastDialogueCompactionSnapshotDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastDialogueRefreshSnapshotDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastDialogueSnapshotItemDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastInfoRedisDto;
import com.example.sku_sw.domain.broadcast.enums.BroadcastDataStatus;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;
import com.example.sku_sw.domain.character.enums.Emotion;
import com.example.sku_sw.global.exception.CustomException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 방송 관련 Redis 저장/삭제를 수행하는 Util 함수
 */
@Slf4j
@Component
public class BroadcastRedisUtil {
    private static final String BROADCAST_CHARACTER_KEY_PREFIX = "BroadcastCharacter:";
    private static final String BROADCAST_INFO_KEY_PREFIX = "BroadcastInfo:";
    private static final String BROADCAST_INFO_CURSOR_SEQUENCE_KEY = "BroadcastInfoCursor:GlobalSeq";
    private static final String BROADCAST_INFO_SUMMARY_PROGRESS_KEY_PREFIX = "BroadcastInfoSummaryInProgress:";
    private static final Long SUMMARY_CURSOR_ID = 0L;
    private static final String DEFAULT_SUMMARY_CONTENT = "(오늘 방송 요약 없음)";
    private static final Duration SUMMARY_PROGRESS_TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public BroadcastRedisUtil(
            @Qualifier("broadcastStringRedisTemplate") StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 방송 캐릭터 정보를 Redis에 저장하는 함수
     * - Key: BroadcastCharacter:{broadcastStreamId}
     * - Value: 방송 캐릭터 정보 JSON
     * @param broadcastStreamId : 방송 스트림 ID
     * @param value : 저장할 방송 캐릭터 정보
     */
    public void setBroadcastCharacterValue(String broadcastStreamId, BroadcastCharacterRedisDto value) {
        String key = BROADCAST_CHARACTER_KEY_PREFIX + broadcastStreamId;
        try {
            String jsonValue = objectMapper.writeValueAsString(value);
            ValueOperations<String, String> values = redisTemplate.opsForValue();
            values.set(key, jsonValue);
        } catch (JsonProcessingException e) {
            log.error("Broadcast Redis Value JSON 변환 오류: {}", e.getMessage());
            throw new RuntimeException("Broadcast Redis Save Error", e);
        }
    }

    /**
     * 방송 캐릭터 정보를 Redis에서 삭제하는 함수
     * @param broadcastStreamId : 삭제할 방송 스트림 ID
     */
    public void deleteBroadcastCharacterValue(String broadcastStreamId) {
        String key = BROADCAST_CHARACTER_KEY_PREFIX + broadcastStreamId;
        redisTemplate.delete(key);
        log.debug("[BroadcastRedisUtil] 방송 캐릭터 정보가 Redis에서 삭제되었습니다. streamId: {}", broadcastStreamId);
    }

    /**
     * Redis에 방송 캐릭터 정보가 존재하는지 확인하는 함수
     * @param broadcastStreamId : 확인할 방송 스트림 ID
     * @return : 키 존재 여부
     */
    public boolean hasBroadcastCharacterValue(String broadcastStreamId) {
        String key = BROADCAST_CHARACTER_KEY_PREFIX + broadcastStreamId;
        Boolean hasKey = redisTemplate.hasKey(key);
        return Boolean.TRUE.equals(hasKey);
    }

    /**
     * Redis에서 방송 캐릭터 정보 JSON 문자열을 조회하는 함수
     * @param broadcastStreamId : 조회할 방송 스트림 ID
     * @return : 방송 캐릭터 정보 JSON 문자열 (없으면 null)
     */
    public String getBroadcastCharacterValue(String broadcastStreamId) {
        String key = BROADCAST_CHARACTER_KEY_PREFIX + broadcastStreamId;
        ValueOperations<String, String> values = redisTemplate.opsForValue();
        return values.get(key);
    }

    /**
     * Redis에 방송 캐릭터 정보 JSON 문자열을 직접 저장하는 함수 (백업 복구용)
     * @param broadcastStreamId : 저장할 방송 스트림 ID
     * @param jsonValue : 저장할 JSON 문자열
     */
    public void setBroadcastCharacterValueRaw(String broadcastStreamId, String jsonValue) {
        String key = BROADCAST_CHARACTER_KEY_PREFIX + broadcastStreamId;
        ValueOperations<String, String> values = redisTemplate.opsForValue();
        values.set(key, jsonValue);
    }

    /**
     * Redis에서 방송 캐릭터 정보 DTO를 조회하는 함수
     * - BroadcastCharacter:{broadcastStreamId} key의 JSON을 파싱하여 DTO로 반환한다.
     * @param broadcastStreamId : 조회할 방송 스트림 ID
     * @return : 방송 캐릭터 정보 DTO
     * @throws CustomException : Redis에 데이터가 없는 경우 BROADCAST_CHARACTER_REDIS_NOT_FOUND
     */
    public BroadcastCharacterRedisDto getBroadcastCharacterDto(String broadcastStreamId) {
        String json = getBroadcastCharacterValue(broadcastStreamId);
        if (json == null) {
            log.warn("[BroadcastRedisUtil] Broadcast character not found in Redis | streamId: {}", broadcastStreamId);
            throw new CustomException(BroadcastErrorCode.BROADCAST_CHARACTER_REDIS_NOT_FOUND);
        }
        try {
            return objectMapper.readValue(json, BroadcastCharacterRedisDto.class);
        } catch (JsonProcessingException e) {
            log.error("[BroadcastRedisUtil] Broadcast character JSON parsing error | streamId: {}, error: {}", broadcastStreamId, e.getMessage());
            throw new RuntimeException("Broadcast Redis Parse Error", e);
        }
    }

    /**
     * Redis에 저장된 방송 캐릭터의 isTalking 필드를 갱신하는 함수
     * - 기존 DTO를 조회하여 isTalking 값만 변경한 뒤 Redis에 다시 저장한다.
     * @param broadcastStreamId : 갱신할 방송 스트림 ID
     * @param isTalking : 설정할 isTalking 값
     */
    public void updateBroadcastCharacterIsTalking(String broadcastStreamId, boolean isTalking) {
        BroadcastCharacterRedisDto existing = getBroadcastCharacterDto(broadcastStreamId);
        existing.setIsTalking(isTalking);
        setBroadcastCharacterValue(broadcastStreamId, existing);
        log.debug("[BroadcastRedisUtil] updateBroadcastCharacterIsTalking() - Updated | streamId: {}, isTalking: {}", broadcastStreamId, isTalking);
    }

    /**
     * 방송 시작 시 summary slot을 초기화하는 함수
     * - 0번 인덱스에 기본 summary DTO를 저장한다.
     * @param broadcastStreamId : 방송 스트림 ID
     */
    public void initializeSummarySlot(String broadcastStreamId) {
        String key = getBroadcastInfoKey(broadcastStreamId);
        deleteBroadcastInfo(broadcastStreamId);
        try {
            BroadcastInfoRedisDto defaultSummary = createSummaryDto(DEFAULT_SUMMARY_CONTENT);
            redisTemplate.opsForList().rightPush(key, objectMapper.writeValueAsString(defaultSummary));
            log.info("[BroadcastRedisUtil] initializeSummarySlot() - Initialized | streamId: {}", broadcastStreamId);
        } catch (JsonProcessingException e) {
            log.error("[BroadcastRedisUtil] initializeSummarySlot() - JSON conversion error | streamId: {}, error: {}", broadcastStreamId, e.getMessage());
            throw new RuntimeException("Broadcast Redis Save Error", e);
        }
    }

    /**
     * 방송 대화 내역을 Redis List에 추가하는 함수
     * - Key: BroadcastInfo:{broadcastStreamId}
     * - Value: cursorId와 dataStatus가 포함된 BroadcastInfoRedisDto JSON (Right Push)
     * @param broadcastStreamId : 방송 스트림 ID
     * @return : cursorId가 주입되어 Redis에 저장된 방송 대화 정보
     */
    public BroadcastInfoRedisDto pushBroadcastInfo(String broadcastStreamId, DialogueSubject subject, String content) {
        return pushBroadcastInfo(broadcastStreamId, subject, content, null);
    }

    /**
     * 방송 대화 내역을 Redis List에 추가하는 함수
     * - Key: BroadcastInfo:{broadcastStreamId}
     * - Value: cursorId와 dataStatus, emotion이 포함된 BroadcastInfoRedisDto JSON (Right Push)
     * @param broadcastStreamId : 방송 스트림 ID
     * @param subject : 대화 주체
     * @param content : 대화 내용
     * @param emotion : 응답 감정값
     * @return : cursorId가 주입되어 Redis에 저장된 방송 대화 정보
     */
    public BroadcastInfoRedisDto pushBroadcastInfo(String broadcastStreamId, DialogueSubject subject, String content, Emotion emotion) {
        String key = getBroadcastInfoKey(broadcastStreamId);
        ensureSummarySlotExists(broadcastStreamId);
        try {
            Long nextCursorId = redisTemplate.opsForValue().increment(BROADCAST_INFO_CURSOR_SEQUENCE_KEY);
            BroadcastInfoRedisDto redisValue = BroadcastInfoRedisDto.builder()
                    .cursorId(nextCursorId)
                    .subject(subject)
                    .content(content)
                    .emotion(emotion)
                    .createdAt(LocalDateTime.now())
                    .dataStatus(BroadcastDataStatus.ACTIVE)
                    .build();

            String jsonValue = objectMapper.writeValueAsString(redisValue);
            redisTemplate.opsForList().rightPush(key, jsonValue);
            log.debug("[BroadcastRedisUtil] pushBroadcastInfo() - Pushed | streamId: {}, cursorId: {}, subject: {}",
                    broadcastStreamId, redisValue.cursorId(), redisValue.subject());
            return redisValue;
        } catch (JsonProcessingException e) {
            log.error("[BroadcastRedisUtil] Broadcast Info JSON 변환 오류 | streamId: {}, error: {}", broadcastStreamId, e.getMessage());
            throw new RuntimeException("Broadcast Redis Save Error", e);
        }
    }

    /**
     * Redis List에서 summary를 제외한 최근 ACTIVE 방송 대화 내역을 조회하는 함수
     * @param broadcastStreamId : 방송 스트림 ID
     * @param limit : 조회할 최대 개수
     * @return : ACTIVE 상태의 방송 대화 정보 DTO 목록
     */
    public List<BroadcastInfoRedisDto> getRecentActiveDialogues(String broadcastStreamId, int limit) {
        List<BroadcastInfoRedisDto> allInfos = getBroadcastInfos(broadcastStreamId);
        if (allInfos.size() <= 1) {
            return List.of();
        }

        List<BroadcastInfoRedisDto> activeInfos = allInfos.stream()
                .skip(1)
                .filter(info -> info.dataStatus() == BroadcastDataStatus.ACTIVE)
                .toList();

        if (activeInfos.size() <= limit) {
            return activeInfos;
        }
        return activeInfos.subList(activeInfos.size() - limit, activeInfos.size());
    }

    /**
     * refresh용 ACTIVE 스냅샷을 조회하는 함수
     * - summary와 최근 ACTIVE 대화 목록, 마지막 cursor를 함께 반환한다.
     * @param broadcastStreamId : 방송 스트림 ID
     * @param limit : 조회할 최대 ACTIVE 개수
     * @return : refresh 스냅샷 DTO
     */
    public BroadcastDialogueRefreshSnapshotDto getRefreshSnapshot(String broadcastStreamId, int limit) {
        // 1. 현재 Redis의 전체 대화 중 ACTIVE인 데이터와 요약 데이터를 가져온다.
        List<BroadcastInfoRedisDto> allInfos = getBroadcastInfos(broadcastStreamId);
        BroadcastInfoRedisDto summary = allInfos.isEmpty() ? createSummaryDto(DEFAULT_SUMMARY_CONTENT) : allInfos.get(0);
        List<BroadcastInfoRedisDto> activeInfos = allInfos.stream()
                .skip(1)
                .filter(info -> info.dataStatus() == BroadcastDataStatus.ACTIVE)
                .toList();

        // 2. 전체 대화 기록을 snapshot으로 저장하고, 해당 대화 중 가장 마지막 대화의 cursor Id를 저장한다.
        List<BroadcastInfoRedisDto> snapshotDialogues;
        if (activeInfos.size() <= limit) {
            snapshotDialogues = activeInfos;
        } else {
            snapshotDialogues = activeInfos.subList(activeInfos.size() - limit, activeInfos.size());
        }
        Long snapshotCursorId = snapshotDialogues.isEmpty()
                ? summary.cursorId()
                : snapshotDialogues.get(snapshotDialogues.size() - 1).cursorId();

        return BroadcastDialogueRefreshSnapshotDto.builder()
                .summary(summary)
                .dialogues(snapshotDialogues)
                .snapshotCursorId(snapshotCursorId)
                .build();
    }

    /**
     * Redis List에서 지정된 cursor를 초과하는 ACTIVE 상태 대화를 조회하는 함수
     * @param broadcastStreamId : 방송 스트림 ID
     * @param cursorId : 기준 cursor
     * @return : cursor 초과 ACTIVE 대화 목록
     */
    public List<BroadcastInfoRedisDto> getActiveDialoguesAfterCursor(String broadcastStreamId, Long cursorId) {
        return getBroadcastInfos(broadcastStreamId).stream()
                .skip(1)
                .filter(info -> info.dataStatus() == BroadcastDataStatus.ACTIVE)
                .filter(info -> cursorId == null || info.cursorId() > cursorId)
                .toList();
    }

    /**
     * Redis List에서 summary를 제외한 ACTIVE 방송 대화 내역 중 cursor 이하이며 subject 필터에 해당하는 데이터를 조회하는 함수
     * @param broadcastStreamId : 방송 스트림 ID
     * @param cursorId : 조회 시작 cursorId (inclusive)
     * @param limit : 조회할 최대 개수
     * @param subjects : 조회할 대화 주체 목록
     * @return : 필터링된 방송 대화 정보 DTO 목록 (오름차순)
     */
    public List<BroadcastInfoRedisDto> getActiveDialoguesByCursor(
            String broadcastStreamId,
            Long cursorId,
            int limit,
            Collection<DialogueSubject> subjects
    ) {
        List<BroadcastInfoRedisDto> allInfos = getBroadcastInfos(broadcastStreamId);
        if (allInfos.size() <= 1) {
            return List.of();
        }

        List<BroadcastInfoRedisDto> filteredInfos = allInfos.stream()
                .skip(1)
                .filter(info -> info.dataStatus() == BroadcastDataStatus.ACTIVE)
                .filter(info -> info.cursorId() <= cursorId)
                .filter(info -> subjects.contains(info.subject()))
                .toList();

        if (filteredInfos.size() <= limit) {
            return filteredInfos;
        }
        return filteredInfos.subList(filteredInfos.size() - limit, filteredInfos.size());
    }

    /**
     * Redis List의 0번 summary를 조회하는 함수
     * @param broadcastStreamId : 방송 스트림 ID
     * @return : summary DTO
     */
    public BroadcastInfoRedisDto getSummary(String broadcastStreamId) {
        ensureSummarySlotExists(broadcastStreamId);
        List<String> jsonList = redisTemplate.opsForList().range(getBroadcastInfoKey(broadcastStreamId), 0, 0);
        if (jsonList == null || jsonList.isEmpty()) {
            return createSummaryDto(DEFAULT_SUMMARY_CONTENT);
        }
        return deserialize(jsonList.get(0), broadcastStreamId);
    }

    /**
     * ACTIVE 상태의 방송 대화 개수를 반환하는 함수
     * @param broadcastStreamId : 방송 스트림 ID
     * @return : ACTIVE 상태 대화 개수
     */
    public int countActiveDialogues(String broadcastStreamId) {
        return (int) getBroadcastInfos(broadcastStreamId).stream()
                .skip(1)
                .filter(info -> info.dataStatus() == BroadcastDataStatus.ACTIVE)
                .count();
    }

    /**
     * INACTIVE 상태의 방송 대화 존재 여부를 반환하는 함수
     * @param broadcastStreamId : 방송 스트림 ID
     * @return : INACTIVE 존재 여부
     */
    public boolean hasInactiveDialogues(String broadcastStreamId) {
        return getBroadcastInfos(broadcastStreamId).stream()
                .skip(1)
                .anyMatch(info -> info.dataStatus() == BroadcastDataStatus.INACTIVE);
    }

    /**
     * summary 진행 상태를 확인하는 함수
     * @param broadcastStreamId : 방송 스트림 ID
     * @return : 진행 여부
     */
    public boolean isSummaryInProgress(String broadcastStreamId) {
        Boolean hasKey = redisTemplate.hasKey(getSummaryProgressKey(broadcastStreamId));
        return Boolean.TRUE.equals(hasKey);
    }

    /**
     * summary 진행 상태를 저장하는 함수
     * @param broadcastStreamId : 방송 스트림 ID
     */
    public boolean markSummaryInProgress(String broadcastStreamId) {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                getSummaryProgressKey(broadcastStreamId),
                "true",
                SUMMARY_PROGRESS_TTL
        );
        boolean result = Boolean.TRUE.equals(acquired);
        if (result) {
            log.info("[BroadcastRedisUtil] markSummaryInProgress() - Marked | streamId: {}", broadcastStreamId);
        }
        return result;
    }

    /**
     * summary 진행 상태를 제거하는 함수
     * @param broadcastStreamId : 방송 스트림 ID
     */
    public void clearSummaryInProgress(String broadcastStreamId) {
        redisTemplate.delete(getSummaryProgressKey(broadcastStreamId));
        log.info("[BroadcastRedisUtil] clearSummaryInProgress() - Cleared | streamId: {}", broadcastStreamId);
    }

    /**
     * compaction 대상 snapshot을 조회하는 함수
     * - INACTIVE가 하나라도 있으면 INACTIVE만 반환하고, 없으면 ACTIVE만 반환한다.
     * @param broadcastStreamId : 방송 스트림 ID
     * @return : summary + 대상 대화 snapshot DTO
     */
    public BroadcastDialogueCompactionSnapshotDto getCompactionSnapshot(String broadcastStreamId) {
        List<BroadcastInfoRedisDto> allInfos = getBroadcastInfos(broadcastStreamId);
        BroadcastInfoRedisDto summary = allInfos.isEmpty() ? createSummaryDto(DEFAULT_SUMMARY_CONTENT) : allInfos.get(0);

        boolean hasInactive = allInfos.stream()
                .skip(1)
                .anyMatch(info -> info.dataStatus() == BroadcastDataStatus.INACTIVE);

        BroadcastDataStatus targetStatus = hasInactive ? BroadcastDataStatus.INACTIVE : BroadcastDataStatus.ACTIVE;
        List<BroadcastDialogueSnapshotItemDto> snapshotItems = new ArrayList<>();

        for (int index = 1; index < allInfos.size(); index++) {
            BroadcastInfoRedisDto info = allInfos.get(index);
            if (info.dataStatus() == targetStatus) {
                snapshotItems.add(BroadcastDialogueSnapshotItemDto.builder()
                        .listIndex(index)
                        .dialogue(info)
                        .build());
            }
        }

        return BroadcastDialogueCompactionSnapshotDto.builder()
                .summary(summary)
                .dialogues(snapshotItems)
                .build();
    }

    /**
     * 지정된 Redis List index의 대화 상태를 INACTIVE로 변경하는 함수
     * @param broadcastStreamId : 방송 스트림 ID
     * @param indices : INACTIVE로 변경할 Redis List index 목록
     */
    public void markDialoguesInactive(String broadcastStreamId, List<Integer> indices) {
        log.info("[BroadcastRedisUtil] markDialoguesInactive() - START | streamId: {}, indicesSize: {}", broadcastStreamId, indices.size());

        /*
            1. Redis List 전체 조회
            - 지정된 index에 해당하는 대화만 상태를 변경하기 위해 전체 목록을 가져온다.
         */
        String key = getBroadcastInfoKey(broadcastStreamId);
        List<String> jsonList = redisTemplate.opsForList().range(key, 0, -1);
        if (jsonList == null || jsonList.isEmpty()) {
            log.info("[BroadcastRedisUtil] markDialoguesInactive() - END | streamId: {}, action: no_data", broadcastStreamId);
            return;
        }

        /*
            2. 대상 index만 ACTIVE -> INACTIVE 변경
            - 새로 append된 ACTIVE 대화는 건드리지 않도록 snapshot index만 갱신한다.
         */
        for (Integer index : indices) {
            if (index == null || index <= 0 || index >= jsonList.size()) {
                continue;
            }
            BroadcastInfoRedisDto info = deserialize(jsonList.get(index), broadcastStreamId);
            if (info.dataStatus() != BroadcastDataStatus.ACTIVE) {
                continue;
            }

            BroadcastInfoRedisDto updatedInfo = BroadcastInfoRedisDto.builder()
                    .cursorId(info.cursorId())
                    .subject(info.subject())
                    .content(info.content())
                    .emotion(info.emotion())
                    .createdAt(info.createdAt())
                    .dataStatus(BroadcastDataStatus.INACTIVE)
                    .build();
            redisTemplate.opsForList().set(key, index, serialize(updatedInfo, broadcastStreamId));
        }

        log.info("[BroadcastRedisUtil] markDialoguesInactive() - END | streamId: {}", broadcastStreamId);
    }

    /**
     * summary를 갱신하고 INACTIVE 대화를 원자적으로 삭제하는 함수
     * @param broadcastStreamId : 방송 스트림 ID
     * @param summaryDto : 반영할 summary DTO
     */
    public void atomicReplaceSummaryAndDeleteInactive(String broadcastStreamId, BroadcastInfoRedisDto summaryDto) {
        String key = getBroadcastInfoKey(broadcastStreamId);
        String summaryJson = serialize(summaryDto, broadcastStreamId);

        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText("""
                local values = redis.call('LRANGE', KEYS[1], 0, -1)
                redis.call('DEL', KEYS[1])
                redis.call('RPUSH', KEYS[1], ARGV[1])
                for i = 2, #values do
                    local item = cjson.decode(values[i])
                    if item['dataStatus'] ~= 'INACTIVE' then
                        redis.call('RPUSH', KEYS[1], values[i])
                    end
                end
                return redis.call('LLEN', KEYS[1])
                """);

        redisTemplate.execute(script, Collections.singletonList(key), summaryJson);
        log.info("[BroadcastRedisUtil] atomicReplaceSummaryAndDeleteInactive() - Applied | streamId: {}", broadcastStreamId);
    }

    /**
     * 지정된 cursor 이하 대화 데이터를 원자적으로 삭제하는 함수
     * - summary slot은 유지한다.
     * @param broadcastStreamId : 방송 스트림 ID
     * @param cursorId : 삭제 기준 cursor
     */
    public void atomicDeleteDialoguesUpToCursor(String broadcastStreamId, Long cursorId) {
        String key = getBroadcastInfoKey(broadcastStreamId);
        String cursorArg = cursorId == null ? String.valueOf(SUMMARY_CURSOR_ID) : String.valueOf(cursorId);

        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText("""
                local values = redis.call('LRANGE', KEYS[1], 0, -1)
                if #values == 0 then
                    return 0
                end
                local cursor = tonumber(ARGV[1])
                redis.call('DEL', KEYS[1])
                redis.call('RPUSH', KEYS[1], values[1])
                for i = 2, #values do
                    local item = cjson.decode(values[i])
                    local itemCursor = tonumber(item['cursorId'])
                    if itemCursor == nil or itemCursor > cursor then
                        redis.call('RPUSH', KEYS[1], values[i])
                    end
                end
                return redis.call('LLEN', KEYS[1])
                """);

        redisTemplate.execute(script, Collections.singletonList(key), cursorArg);
        log.info("[BroadcastRedisUtil] atomicDeleteDialoguesUpToCursor() - Applied | streamId: {}, cursorId: {}", broadcastStreamId, cursorId);
    }

    /**
     * 방송 대화 내역 Redis List를 삭제하는 함수
     * @param broadcastStreamId : 삭제할 방송 스트림 ID
     */
    public void deleteBroadcastInfo(String broadcastStreamId) {
        String key = getBroadcastInfoKey(broadcastStreamId);
        redisTemplate.delete(key);
        clearSummaryInProgress(broadcastStreamId);
        log.debug("[BroadcastRedisUtil] deleteBroadcastInfo() - Deleted | streamId: {}", broadcastStreamId);
    }

    private List<BroadcastInfoRedisDto> getBroadcastInfos(String broadcastStreamId) {
        ensureSummarySlotExists(broadcastStreamId);
        List<String> jsonList = redisTemplate.opsForList().range(getBroadcastInfoKey(broadcastStreamId), 0, -1);
        if (jsonList == null || jsonList.isEmpty()) {
            return List.of();
        }
        return jsonList.stream()
                .map(json -> deserialize(json, broadcastStreamId))
                .filter(Objects::nonNull)
                .toList();
    }

    private BroadcastInfoRedisDto createSummaryDto(String summaryContent) {
        return BroadcastInfoRedisDto.builder()
                .cursorId(SUMMARY_CURSOR_ID)
                .subject(DialogueSubject.SYSTEM_SUMMARY)
                .content(summaryContent)
                .emotion(null)
                .createdAt(LocalDateTime.now())
                .dataStatus(BroadcastDataStatus.ACTIVE)
                .build();
    }

    private void ensureSummarySlotExists(String broadcastStreamId) {
        String key = getBroadcastInfoKey(broadcastStreamId);
        Long size = redisTemplate.opsForList().size(key);
        if (size == null || size == 0L) {
            try {
                redisTemplate.opsForList().rightPush(key, serialize(createSummaryDto(DEFAULT_SUMMARY_CONTENT), broadcastStreamId));
            } catch (Exception e) {
                log.error("[BroadcastRedisUtil] ensureSummarySlotExists() - Failed | streamId: {}, error: {}", broadcastStreamId, e.getMessage());
                throw e;
            }
        }
    }

    private String getBroadcastInfoKey(String broadcastStreamId) {
        return BROADCAST_INFO_KEY_PREFIX + broadcastStreamId;
    }

    private String getSummaryProgressKey(String broadcastStreamId) {
        return BROADCAST_INFO_SUMMARY_PROGRESS_KEY_PREFIX + broadcastStreamId;
    }

    private String serialize(BroadcastInfoRedisDto dto, String broadcastStreamId) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            log.error("[BroadcastRedisUtil] Broadcast Info JSON 변환 오류 | streamId: {}, error: {}", broadcastStreamId, e.getMessage());
            throw new RuntimeException("Broadcast Redis Save Error", e);
        }
    }

    private BroadcastInfoRedisDto deserialize(String json, String broadcastStreamId) {
        try {
            return objectMapper.readValue(json, BroadcastInfoRedisDto.class);
        } catch (JsonProcessingException e) {
            log.error("[BroadcastRedisUtil] Broadcast Info JSON 파싱 오류 | streamId: {}, error: {}", broadcastStreamId, e.getMessage());
            throw new RuntimeException("Broadcast Redis Parse Error", e);
        }
    }
}
