package com.example.sku_sw.domain.character.controller;

import com.example.sku_sw.domain.character.dto.CharacterCreateReqDto;
import com.example.sku_sw.domain.character.dto.CharacterDetailResDto;
import com.example.sku_sw.domain.character.dto.CharacterListResDto;
import com.example.sku_sw.domain.character.dto.CharacterSelectReqDto;
import com.example.sku_sw.domain.character.dto.CharacterSelectResDto;
import com.example.sku_sw.domain.character.dto.CharacterSettingsResDto;
import com.example.sku_sw.domain.character.dto.CharacterUpdateReqDto;
import com.example.sku_sw.domain.character.service.CharacterService;
import com.example.sku_sw.global.response.GlobalResponse;
import com.example.sku_sw.global.response.SliceResponse;
import com.example.sku_sw.global.security.SecurityUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
public class CharacterController implements CharacterControllerDocs {

    private final CharacterService characterService;

    @Override
    public ResponseEntity<GlobalResponse<Void>> createCharacter(
            @Valid CharacterCreateReqDto characterCreateReqDto
    ) {
        Long userId = SecurityUtil.getCurrentUserId();
        characterService.createCharacter(userId, characterCreateReqDto);
        return ResponseEntity.ok(GlobalResponse.success("캐릭터 생성 완료", null));
    }

    @Override
    public ResponseEntity<GlobalResponse<SliceResponse<CharacterListResDto>>> getCharacterList(
            @RequestHeader(value = "Origin", required = false) String origin,
            int page,
            int size
    ) {
        log.info("[CharacterController] createCharacter 요청 발생 - Origin: {}", origin);
        Long userId = SecurityUtil.getCurrentUserId();
        SliceResponse<CharacterListResDto> response = characterService.getCharacterList(userId, page, size);
        return ResponseEntity.ok(GlobalResponse.success("캐릭터 리스트 조회 완료", response));
    }

    @Override
    public ResponseEntity<GlobalResponse<CharacterDetailResDto>> getCharacterDetail(
            Long characterId
    ) {
        Long userId = SecurityUtil.getCurrentUserId();
        CharacterDetailResDto response = characterService.getCharacterDetail(userId, characterId);
        return ResponseEntity.ok(GlobalResponse.success("캐릭터 상세 조회 완료", response));
    }

    @Override
    public ResponseEntity<GlobalResponse<CharacterSettingsResDto>> getSettings() {
        CharacterSettingsResDto response = characterService.getSettings();
        return ResponseEntity.ok(GlobalResponse.success("설정 옵션 조회 완료", response));
    }

    @Override
    public ResponseEntity<GlobalResponse<Void>> updateCharacter(
            Long characterId,
            @Valid CharacterUpdateReqDto characterUpdateReqDto
    ) {
        Long userId = SecurityUtil.getCurrentUserId();
        characterService.updateCharacter(userId, characterId, characterUpdateReqDto);
        return ResponseEntity.ok(GlobalResponse.success("캐릭터 수정 완료", null));
    }

    @Override
    public ResponseEntity<GlobalResponse<CharacterSelectResDto>> selectCharacter(
            Long characterId,
            @Valid CharacterSelectReqDto characterSelectReqDto
    ) {
        Long userId = SecurityUtil.getCurrentUserId();
        CharacterSelectResDto response = characterService.selectCharacter(userId, characterId, characterSelectReqDto.isSelected());
        return ResponseEntity.ok(GlobalResponse.success("캐릭터 선택 완료", response));
    }

    @Override
    public ResponseEntity<GlobalResponse<Void>> deleteCharacter(Long characterId) {
        Long userId = SecurityUtil.getCurrentUserId();
        characterService.deleteCharacter(userId, characterId);
        return ResponseEntity.ok(GlobalResponse.success("캐릭터 삭제 완료", null));
    }
}
