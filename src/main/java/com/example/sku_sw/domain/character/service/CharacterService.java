package com.example.sku_sw.domain.character.service;

import com.example.sku_sw.domain.character.dto.CharacterCreateReqDto;
import com.example.sku_sw.domain.character.dto.CharacterDetailResDto;
import com.example.sku_sw.domain.character.dto.CharacterListResDto;
import com.example.sku_sw.domain.character.dto.CharacterSelectResDto;
import com.example.sku_sw.domain.character.dto.CharacterSettingsResDto;
import com.example.sku_sw.domain.character.dto.CharacterUpdateReqDto;
import com.example.sku_sw.domain.character.dto.CharacterVrmResDto;
import com.example.sku_sw.domain.character.entity.Character;
import com.example.sku_sw.domain.character.entity.CharacterImage;
import com.example.sku_sw.domain.character.entity.CharacterVrm;
import com.example.sku_sw.domain.character.entity.CharacterImageDetail;
import com.example.sku_sw.domain.character.entity.CharacterPersona;
import com.example.sku_sw.domain.character.entity.CharacterTriggerWord;
import com.example.sku_sw.domain.character.enums.*;
import com.example.sku_sw.domain.character.mapper.CharacterMapper;
import com.example.sku_sw.domain.character.repository.CharacterImageDetailRepository;
import com.example.sku_sw.domain.character.repository.CharacterImageRepository;
import com.example.sku_sw.domain.character.repository.CharacterPersonaRepository;
import com.example.sku_sw.domain.character.repository.CharacterRepository;
import com.example.sku_sw.domain.character.repository.CharacterTriggerWordRepository;
import com.example.sku_sw.domain.character.repository.CharacterVrmRepository;
import com.example.sku_sw.domain.user.entity.User;
import com.example.sku_sw.domain.user.repository.UserRepository;
import com.example.sku_sw.global.exception.CustomException;
import com.example.sku_sw.global.util.S3Util;
import com.example.sku_sw.global.response.SliceResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterService {

    private final CharacterRepository characterRepository;
    private final CharacterPersonaRepository characterPersonaRepository;
    private final CharacterTriggerWordRepository characterTriggerWordRepository;
    private final CharacterImageRepository characterImageRepository;
    private final CharacterImageDetailRepository characterImageDetailRepository;
    private final UserRepository userRepository;
    private final CharacterMapper characterMapper;
    private final CharacterVrmRepository characterVrmRepository;
    private final S3Util s3Util;

    /**
     * 캐릭터 생성
     * - 사용자가 새로운 캐릭터를 생성한다.
     * - 외형 타입(2D/3D)에 따라 CharacterImage 또는 CharacterVrm을 조회한다.
     * - 성별 검증, 호출어 정규화, 페르소나 설정 등을 처리한다.
     * @param userId : 캐릭터를 생성하는 사용자 ID
     * @param req : 캐릭터 생성 요청 DTO
     */
    @Transactional
    public void createCharacter(Long userId, CharacterCreateReqDto req) {
        log.info("[CharacterService] createCharacter() - START | userId: {}, characterName: {}", userId, req.characterName());

        /*
            1. 사용자 조회
            - userId로 사용자를 조회하고, 존재하지 않으면 USER_NOT_FOUND 예외를 발생시킨다.
        */
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(CharacterErrorCode.USER_NOT_FOUND));

        /*
            2. 외형 타입에 따라 CharacterImage 또는 CharacterVrm 조회 및 성별 검증
            - TWO_D: CharacterImage를 조회하고 성별을 검증한다.
            - THREE_D: CharacterVrm을 조회하고 성별을 검증한다.
        */
        CharacterImage characterImage = null;
        CharacterVrm characterVrm = null;

        if (req.characterAppearanceType() == CharacterAppearanceType.TWO_D) {
            characterImage = characterImageRepository.findById(req.targetId())
                    .orElseThrow(() -> new CustomException(CharacterErrorCode.CHARACTER_IMAGE_NOT_FOUND));
            if (characterImage.getGender() != req.gender()) {
                throw new CustomException(CharacterErrorCode.CHARACTER_IMAGE_TYPE_GENDER_MISMATCH);
            }
        } else {
            characterVrm = characterVrmRepository.findById(req.targetId())
                    .orElseThrow(() -> new CustomException(CharacterErrorCode.CHARACTER_VRM_NOT_FOUND));
            if (characterVrm.getGender() != req.gender()) {
                throw new CustomException(CharacterErrorCode.CHARACTER_VRM_TYPE_GENDER_MISMATCH);
            }
        }

        /*
            3. 호출어 정규화
            - trim, 중복 제거, 오름차순 정렬, 최대 3개 검증, 최소 1개 검증
        */
        List<String> normalizedTriggerWords = normalizeTriggerWords(req.triggerWords());

        /*
            4. Character 엔티티 생성 (persona 없이 먼저 생성)
            - Builder 패턴을 사용하여 Character 엔티티를 생성한다.
            - 외형 타입에 따라 characterImage 또는 characterVrm 중 하나만 설정된다.
        */
        Character character = Character.builder()
                .user(user)
                .name(req.characterName())
                .gender(req.gender())
                .characterAppearanceType(req.characterAppearanceType())
                .characterImage(characterImage)
                .characterVrm(characterVrm)
                .build();

        /*
            5. Character 저장 (ID 할당을 위해 먼저 저장)
            - CharacterPersona는 Character의 ID가 필요하므로 먼저 저장한다.
        */
        character = characterRepository.save(character);

        /*
            6. CharacterPersona 생성 및 저장 (별도 저장 필요)
            - OneToOne 관계에서 CharacterPersona는 owning side이므로 별도 저장이 필요하다.
        */
        CharacterPersona persona = CharacterPersona.builder()
                .character(character)
                .presetType(req.characterPersona().presetType())
                .build();
        characterPersonaRepository.save(persona);

        /*
            7. CharacterTriggerWord 엔티티 리스트 생성 및 추가
            - 정규화된 호출어를 기반으로 CharacterTriggerWord 엔티티 리스트를 생성한다.
            - sortOrder는 0부터 시작한다.
        */
        List<CharacterTriggerWord> triggerWordEntities = new ArrayList<>();
        for (int i = 0; i < normalizedTriggerWords.size(); i++) {
            triggerWordEntities.add(CharacterTriggerWord.builder()
                    .character(character)
                    .word(normalizedTriggerWords.get(i))
                    .sortOrder(i)
                    .build());
        }
        character.getTriggerWords().addAll(triggerWordEntities);
        character = characterRepository.save(character);
        log.info("[CharacterService] createCharacter() - END | characterId: {}", character.getId());
    }

    /**
     * 캐릭터 목록 조회 (무한스크롤)
     * - 사용자가 생성한 캐릭터 목록을 페이징하여 조회한다.
     * @param userId : 조회할 사용자 ID
     * @param page : 페이지 번호 (1부터 시작, 프론트 기준)
     * @param size : 페이지 크기
     * @return : 캐릭터 목록 SliceResponse
     */
    @Transactional(readOnly = true)
    public SliceResponse<CharacterListResDto> getCharacterList(Long userId, int page, int size) {
        log.info("[CharacterService] getCharacterList() - START | userId: {}, page: {}, size: {}", userId, page, size);

        /*
            1. 사용자 정보 조회
            - 선택된 캐릭터의 PK를 가져온다.
         */
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(CharacterErrorCode.USER_NOT_FOUND));

        /*
            2. 조건에 맞게 Character 정보 페이지 네이션 조회
            - 현재 사용자가 선택한 Character가 있는 경우 & page=1인 경우
                : 최상단에 선택된 Character의 정보 위치
            - 현재 사용자가 선택한 Character가 있지만 page!=1인 경우
                : 기존처럼 조회
            - 현재 사용자가 선택한 Character가 없는 경우
                : 기존처럼 조회
         */
        Long selectedCharacterId = user.getSelectedCharacterId();
        Long sortTargetId = selectedCharacterId != null ? selectedCharacterId : -1L; // selectedCharacterId가 null인 경우엔 임시 데이터 설정

        Pageable pageable = PageRequest.of(page - 1, size);
        Slice<Character> slice = characterRepository.findCharactersWithSelectedFirst(userId, sortTargetId, pageable);

        /*
            3. Slice 조회 결과 mapping
         */
        SliceResponse<CharacterListResDto> result = SliceResponse.of(slice, character -> {
            // 1. 외형 URL 추출 (2D: 캐릭터 이미지, 3D: VRM 썸네일)
            String imageUrl = "";
            if (character.getCharacterAppearanceType() == CharacterAppearanceType.TWO_D
                    && character.getCharacterImage() != null
                    && !character.getCharacterImage().getImageDetails().isEmpty()) {
                imageUrl = s3Util.createFullCharacterImageUrl(character.getCharacterImage().getImageDetails().get(0).getImageUrl());
            } else if (character.getCharacterAppearanceType() == CharacterAppearanceType.THREE_D
                    && character.getCharacterVrm() != null) {
                imageUrl = s3Util.createFullCharacterImageUrl(character.getCharacterVrm().getThumbnailUrl());
            }

            // 2. TriggerWords 추출 (이때도 Hibernate가 배치 사이즈만큼 IN 쿼리로 한 번에 가져옴)
            List<String> triggerWords = character.getTriggerWords().stream()
                    .map(CharacterTriggerWord::getWord)
                    .collect(Collectors.toList());

            // 3. 현재 캐릭터가 선택된 캐릭터인지 확인
            boolean isSelected = selectedCharacterId != null && selectedCharacterId.equals(character.getId());

            // 4. DTO 변환 반환
            return CharacterListResDto.builder()
                    .characterId(character.getId())
                    .characterName(character.getName())
                    .gender(character.getGender())
                    .characterImageUrl(imageUrl)
                    .triggerWords(triggerWords)
                    .presetType(character.getCharacterPersona().getPresetType())
                    .isSelected(isSelected) // DTO에 isSelected 필드가 있다면 세팅해 주세요
                    .build();
        });

        log.info("[CharacterService] getCharacterList() - END | resultSize: {}", result.size());
        return result;
    }

    /**
     * 캐릭터 상세 조회
     * - 캐릭터의 상세 정보를 조회한다.
     * @param userId : 조회하는 사용자 ID
     * @param characterId : 조회할 캐릭터 ID
     * @return : 캐릭터 상세 정보
     */
    @Transactional(readOnly = true)
    public CharacterDetailResDto getCharacterDetail(Long userId, Long characterId) {
        log.info("[CharacterService] getCharacterDetail() - START | userId: {}, characterId: {}", userId, characterId);

        /*
            1. 캐릭터 조회
            - characterId와 userId로 캐릭터를 조회하고, 존재하지 않으면 CHARACTER_NOT_FOUND 예외를 발생시킨다.
        */
        Character character = characterRepository.findByIdAndUserId(characterId, userId)
                .orElseThrow(() -> new CustomException(CharacterErrorCode.CHARACTER_NOT_FOUND));

        /*
            2. 호출어 조회
            - characterId로 호출어 목록을 조회하고, sortOrder 기준 오름차순으로 정렬한다.
        */
        List<String> triggerWords = characterTriggerWordRepository
                .findAllByCharacterIdOrderBySortOrderAsc(characterId)
                .stream()
                .map(CharacterTriggerWord::getWord)
                .collect(Collectors.toList());

        /*
            3. User 조회 및 isSelected 계산
            - userId로 User를 조회하고, selectedCharacterId와 characterId를 비교한다.
        */
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(CharacterErrorCode.CHARACTER_NOT_FOUND));

        boolean isSelected = user.getSelectedCharacterId() != null &&
                user.getSelectedCharacterId().equals(characterId);

        /*
            4. 캐릭터 대표 이미지 URL 조회
            - 2D: CharacterImageDetail에서 첫 번째 이미지 URL을 가져온다.
            - 3D: CharacterVrm의 썸네일 URL을 가져온다.
        */
        String characterImageUrl = "";
        if (character.getCharacterAppearanceType() == CharacterAppearanceType.TWO_D
                && character.getCharacterImage() != null) {
            characterImageUrl = s3Util.createFullCharacterImageUrl(getCharacterImageUrl(character.getCharacterImage().getId()));
        } else if (character.getCharacterAppearanceType() == CharacterAppearanceType.THREE_D
                && character.getCharacterVrm() != null) {
            characterImageUrl = s3Util.createFullCharacterImageUrl(character.getCharacterVrm().getThumbnailUrl());
        }

        /*
            5. ResponseDto Mapping
            - Mapper를 사용하여 CharacterDetailResDto로 변환한다.
        */
        CharacterDetailResDto result = characterMapper.toDetailResDto(
                character,
                triggerWords,
                characterImageUrl,
                isSelected
        );

        log.info("[CharacterService] getCharacterDetail() - END | characterId: {}", characterId);
        return result;
    }

    /**
     * 캐릭터 생성 설정 조회
     * - 캐릭터 생성에 필요한 모든 설정 정보를 조회한다.
     * - CharacterImage, CharacterVrm, PresetType 목록을 반환한다.
     * @return : 캐릭터 생성 설정 정보
     */
    @Transactional(readOnly = true)
    public CharacterSettingsResDto getSettings() {
        log.info("[CharacterService] getSettings() - START");

        /*
             1. CharacterImage 목록 조회
             - 모든 CharacterImage를 조회한다. Fetch Join으로 캐릭터 이미지 정보까지 같이 1차 캐시에 로드한다.
         */
        List<CharacterImage> characterImages = characterImageRepository.findAllWithImageDetails();

        /*
             2. CharacterVrm 목록 조회
             - 모든 CharacterVrm을 조회한다.
         */
        List<CharacterVrm> characterVrms = characterVrmRepository.findAll();

        /*
             3. CharacterSettingsResDto 생성
             - characterImages, vrmPresets, personaPresetTypes를 설정한다.
         */
        CharacterSettingsResDto result = CharacterSettingsResDto.builder()
                .characterImages(characterImages.stream()
                        .map(image -> {
                            String imageUrl = s3Util.createFullCharacterImageUrl(image.getImageDetails().getFirst().getImageUrl());
                            return characterMapper.toCharacterImageResDto(image, imageUrl);
                        })
                        .collect(Collectors.toList()))
                .vrmPresets(characterVrms.stream()
                        .map(vrm -> {
                            String thumbnailUrl = s3Util.createFullCharacterImageUrl(vrm.getThumbnailUrl());
                            String vrmUrl = s3Util.createFullCharacterImageUrl(vrm.getVrmUrl());
                            return CharacterVrmResDto.builder()
                                    .characterVrmId(vrm.getId())
                                    .presetId(vrm.getPresetId())
                                    .gender(vrm.getGender())
                                    .name(vrm.getName())
                                    .thumbnailUrl(thumbnailUrl)
                                    .vrmUrl(vrmUrl)
                                    .build();
                        })
                        .collect(Collectors.toList()))
                .personaPresetTypes(Arrays.asList(PresetType.values()))
                .build();

        log.info("[CharacterService] getSettings() - END");
        return result;
    }

    /**
     * 캐릭터 수정
     * - 기존 캐릭터의 정보를 전체 수정한다.
     * - 외형 타입(2D/3D)에 따라 CharacterImage 또는 CharacterVrm을 조회한다.
     * @param userId : 수정하는 사용자 ID
     * @param characterId : 수정할 캐릭터 ID
     * @param req : 캐릭터 수정 요청 DTO
     */
    @Transactional
    public void updateCharacter(Long userId, Long characterId, CharacterUpdateReqDto req) {
        log.info("[CharacterService] updateCharacter() - START | userId: {}, characterId: {}, characterName: {}",
                userId, characterId, req.characterName());

        /*
            1. 캐릭터 조회 및 소유권 검증
            - characterId와 userId로 캐릭터를 조회하고, 존재하지 않으면 CHARACTER_NOT_FOUND 예외를 발생시킨다.
        */
        Character character = characterRepository.findByIdAndUserId(characterId, userId)
                .orElseThrow(() -> new CustomException(CharacterErrorCode.CHARACTER_NOT_FOUND));

        /*
            2. 외형 타입에 따라 CharacterImage 또는 CharacterVrm 조회 및 성별 검증
            - TWO_D: CharacterImage를 조회하고 성별을 검증한다.
            - THREE_D: CharacterVrm을 조회하고 성별을 검증한다.
        */
        CharacterImage characterImage = null;
        CharacterVrm characterVrm = null;

        if (req.characterAppearanceType() == CharacterAppearanceType.TWO_D) {
            characterImage = characterImageRepository.findById(req.targetId())
                    .orElseThrow(() -> new CustomException(CharacterErrorCode.CHARACTER_IMAGE_NOT_FOUND));
            if (characterImage.getGender() != req.gender()) {
                throw new CustomException(CharacterErrorCode.CHARACTER_IMAGE_TYPE_GENDER_MISMATCH);
            }
        } else {
            characterVrm = characterVrmRepository.findById(req.targetId())
                    .orElseThrow(() -> new CustomException(CharacterErrorCode.CHARACTER_VRM_NOT_FOUND));
            if (characterVrm.getGender() != req.gender()) {
                throw new CustomException(CharacterErrorCode.CHARACTER_VRM_TYPE_GENDER_MISMATCH);
            }
        }

        /*
            3. 호출어 정규화
            - trim, 중복 제거, 오름차순 정렬, 최대 3개 검증, 최소 1개 검증
        */
        List<String> normalizedTriggerWords = normalizeTriggerWords(req.triggerWords());

        /*
            4. 기존 CharacterPersona 데이터 수정
            - characterRepository.findByIdAndUserId()에서 CharacterPersona fetch join으로 조회 완료
            - 기존에 존재하던 캐릭터 페르소나 인스턴스의 정보를 수정한다.
        */
        character.getCharacterPersona().updateCharacterPersona(req.characterPersona().presetType());

        /*
            5. 기존 TriggerWords 일괄 삭제
        */
        characterTriggerWordRepository.deleteByCharacterId(characterId);

        /*
            6. 새로운 CharacterTriggerWord 생성
            - 정규화된 호출어를 기반으로 CharacterTriggerWord 엔티티 리스트를 생성한다.
        */
        List<CharacterTriggerWord> newTriggerWords = new ArrayList<>();
        for (int i = 0; i < normalizedTriggerWords.size(); i++) {
            newTriggerWords.add(CharacterTriggerWord.builder()
                    .character(character)
                    .word(normalizedTriggerWords.get(i))
                    .sortOrder(i)
                    .build());
        }
        character.getTriggerWords().addAll(newTriggerWords);

        /*
            7. Character 정보 업데이트
            - name, gender, characterImage, characterVrm을 업데이트한다.
        */
        character.updateCharacter(req.characterName(), req.gender(), characterImage, characterVrm);

        log.info("[CharacterService] updateCharacter() - END | characterId: {}", characterId);
    }

    /**
     * 캐릭터 선택/선택해제
     * - 사용자의 selectedCharacterId를 업데이트한다.
     * - 동시성 제어를 위해 PESSIMISTIC_WRITE 락을 사용한다.
     * @param userId : 사용자 ID
     * @param characterId : 선택/선택해제할 캐릭터 ID
     * @param isSelected : 선택 여부 (true: 선택, false: 선택해제)
     * @return : 선택 결과 (selectedCharacterId, deselectedCharacterId)
     */
    @Transactional
    public CharacterSelectResDto selectCharacter(Long userId, Long characterId, Boolean isSelected) {
        log.info("[CharacterService] selectCharacter() - START | userId: {}, characterId: {}, isSelected: {}",
                userId, characterId, isSelected);

        /*
            1. User 조회 (PESSIMISTIC_WRITE 락)
            - userId로 User를 조회하고, 존재하지 않으면 CHARACTER_NOT_FOUND 예외를 발생시킨다.
            - 동시성 제어를 위해 비관적 쓰기 락을 사용한다.
        */
        User user = userRepository.findByIdWithLock(userId)
                .orElseThrow(() -> new CustomException(CharacterErrorCode.USER_NOT_FOUND));

        /*
            2. 캐릭터 조회 및 소유권 검증
            - characterId와 userId로 캐릭터를 조회하고, 존재하지 않으면 CHARACTER_NOT_FOUND 예외를 발생시킨다.
        */
        Character character = characterRepository.findByIdAndUserId(characterId, userId)
                .orElseThrow(() -> new CustomException(CharacterErrorCode.CHARACTER_NOT_FOUND));

        /*
            3. 이전 선택 캐릭터 ID 저장
            - deselectedCharacterId로 사용할 이전 selectedCharacterId를 저장한다.
        */
        Long deselectedCharacterId = user.getSelectedCharacterId();

        /*
            4. user.selectedCharacterId 업데이트
            - isSelected가 true인 경우 -> 사용자가 선택중인 캐릭터 ID를 characterId로 설정
            - isSelected가 false인 경우
                - 현재 사용자가 선택한 캐릭터가 없는 경우 : 선택을 취소할 선택된 캐릭터가 없음 예외 발생
                - 현재 사용자가 선택한 캐릭터와 동일한 경우 : 선택 취소
                - 현재 사용자가 선택한 캐릭터와 동일하지 않은 경우 : 선택되지 않은 캐릭터 선택 해제 시도 예외 발생
        */
        if (isSelected) {
            user.updateSelectedCharacterId(characterId);
        } else {
            if(user.getSelectedCharacterId() == null){
                throw new CustomException(CharacterErrorCode.NO_CHARACTERS_FOR_DESELECT);
            }
            else if (user.getSelectedCharacterId().equals(characterId)) {
                user.updateSelectedCharacterId(null);
            } else {
                throw new CustomException(CharacterErrorCode.CHARACTER_NOT_SELECTED);
            }
        }

        /*
            5. ResponseDto Mapping
            - Mapper를 사용하여 CharacterSelectResDto로 변환한다.
        */
        CharacterSelectResDto result = characterMapper.toSelectResDto(user.getSelectedCharacterId(), deselectedCharacterId);

        log.info("[CharacterService] selectCharacter() - END | selectedCharacterId: {}, deselectedCharacterId: {}",
                user.getSelectedCharacterId(), deselectedCharacterId);
        return result;
    }

    /**
     * 캐릭터 삭제
     * - 캐릭터를 삭제한다.
     * - cascade 설정으로 CharacterPersona와 CharacterTriggerWord도 함께 삭제된다.
     * - 삭제된 캐릭터가 선택된 캐릭터인 경우 selectedCharacterId를 null로 설정한다.
     * @param userId : 사용자 ID
     * @param characterId : 삭제할 캐릭터 ID
     */
    @Transactional
    public void deleteCharacter(Long userId, Long characterId) {
        log.info("[CharacterService] deleteCharacter() - START | userId: {}, characterId: {}", userId, characterId);

        /*
            1. 사용자 조회 (PESSIMISTIC_WRITE 락)
            - selectedCharacterId 업데이트를 위해 비관적 쓰기 락을 사용한다.
        */
        User user = userRepository.findByIdWithLock(userId)
                .orElseThrow(() -> new CustomException(CharacterErrorCode.USER_NOT_FOUND));

        /*
            2. 캐릭터 조회
        */
        Character character = characterRepository.findByIdAndUserId(characterId, userId)
                .orElseThrow(() -> new CustomException(CharacterErrorCode.CHARACTER_NOT_FOUND));

        /*
            3. selectedCharacterId 업데이트
            - 삭제되는 캐릭터가 선택된 캐릭터인 경우 selectedCharacterId를 null로 설정한다.
        */
        if (user.getSelectedCharacterId() != null && user.getSelectedCharacterId().equals(characterId)) {
            user.updateSelectedCharacterId(null);
        }

        /*
            4. 캐릭터 삭제
            - cascade 설정으로 CharacterPersona와 CharacterTriggerWord도 함께 삭제된다.
        */
        characterRepository.delete(character);
        log.info("[CharacterService] deleteCharacter() - END | characterId: {}", characterId);
    }

    /**
     * 호출어 정규화
     * - trim, 중복 제거, 오름차순 정렬, 최대 3개 검증, 최소 1개 검증
     * @param triggerWords : 원본 호출어 리스트
     * @return : 정규화된 호출어 리스트
     */
    private List<String> normalizeTriggerWords(List<String> triggerWords) {
        List<String> normalized = triggerWords.stream()
                .map(String::trim)
                .filter(word -> !word.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .sorted()
                .collect(Collectors.toList());

        if (normalized.size() > 3) {
            throw new CustomException(CharacterErrorCode.TRIGGER_WORD_EXCEED_MAX);
        }

        if (normalized.isEmpty()) {
            throw new CustomException(CharacterErrorCode.TRIGGER_WORD_EMPTY);
        }

        return normalized;
    }

    /**
     * 캐릭터 이미지 URL 조회
     * - CharacterImageDetail에서 첫 번째 이미지 URL을 가져온다.
     * @param characterImageId : 캐릭터 이미지 ID
     * @return : 캐릭터 이미지 URL (없으면 빈 문자열)
     */
    private String getCharacterImageUrl(Long characterImageId) {
        List<CharacterImageDetail> details = characterImageDetailRepository
                .findAllByCharacterImageIdOrderByEmotionAsc(characterImageId);

        if (details.isEmpty()) {
            return "";
        }

        return details.get(0).getImageUrl();
    }
}
