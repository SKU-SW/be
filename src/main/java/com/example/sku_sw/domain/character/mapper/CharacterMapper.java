package com.example.sku_sw.domain.character.mapper;

import com.example.sku_sw.domain.character.dto.CharacterDetailResDto;
import com.example.sku_sw.domain.character.dto.CharacterImageResDto;
import com.example.sku_sw.domain.character.dto.CharacterListResDto;
import com.example.sku_sw.domain.character.dto.CharacterPersonaResDto;
import com.example.sku_sw.domain.character.dto.CharacterSelectResDto;
import com.example.sku_sw.domain.character.dto.CharacterVrmResDto;
import com.example.sku_sw.domain.character.entity.Character;
import com.example.sku_sw.domain.character.entity.CharacterVrm;
import com.example.sku_sw.domain.character.entity.CharacterImage;
import com.example.sku_sw.domain.character.entity.CharacterPersona;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface CharacterMapper {

    /**
     * Character Entity, triggerWords, isSelected -> CharacterListResDto 변환
     * @param character : 변환할 Character Entity
     * @param triggerWords : 캐릭터 호출어 리스트
     * @param characterImageUrl : 캐릭터 이미지 URL
     * @param isSelected : 선택 여부
     * @return : 변환된 CharacterListResDto
     */
    @Mapping(target = "characterId", source = "character.id")
    @Mapping(target = "characterName", source = "character.name")
    @Mapping(target = "gender", source = "character.gender")
    @Mapping(target = "characterImageUrl", source = "characterImageUrl")
    @Mapping(target = "triggerWords", source = "triggerWords")
    @Mapping(target = "presetType", source = "character.characterPersona.presetType")
    @Mapping(target = "isSelected", source = "isSelected")
    CharacterListResDto toListResDto(Character character, List<String> triggerWords, String characterImageUrl, boolean isSelected);

    /**
     * Character Entity, triggerWords, isSelected -> CharacterDetailResDto 변환
     * @param character : 변환할 Character Entity
     * @param triggerWords : 캐릭터 호출어 리스트
     * @param characterImageUrl : 캐릭터 이미지 URL
     * @param isSelected : 선택 여부
     * @return : 변환된 CharacterDetailResDto
     */
    @Mapping(target = "characterId", source = "character.id")
    @Mapping(target = "characterName", source = "character.name")
    @Mapping(target = "triggerWords", source = "triggerWords")
    @Mapping(target = "gender", source = "character.gender")
    @Mapping(target = "characterImageUrl", source = "characterImageUrl")
    @Mapping(target = "characterPersona", source = "character.characterPersona")
    @Mapping(target = "isSelected", source = "isSelected")
    CharacterDetailResDto toDetailResDto(Character character, List<String> triggerWords, String characterImageUrl, boolean isSelected);

    /**
     * CharacterPersona Entity -> CharacterPersonaResDto 변환
     * @param characterPersona : 변환할 CharacterPersona Entity
     * @return : 변환된 CharacterPersonaResDto
     */
    CharacterPersonaResDto toPersonaResDto(CharacterPersona characterPersona);

    /**
     * CharacterImage Entity -> CharacterImageResDto 변환
     * @param characterImage : 변환할 CharacterImage Entity
     * @param imageUrl : 캐릭터 이미지 URL (대표 이미지)
     * @return : 변환된 CharacterImageResDto
     */
    @Mapping(target = "imageId", source = "characterImage.id")
    @Mapping(target = "name", source = "characterImage.preset")
    @Mapping(target = "imageUrl", source = "imageUrl")
    CharacterImageResDto toCharacterImageResDto(CharacterImage characterImage, String imageUrl);

    /**
     * 선택/선택해제 결과 -> CharacterSelectResDto 변환
     * @param selectedCharacterId : 선택된 캐릭터 ID
     * @param deselectedCharacterId : 선택해제된 캐릭터 ID
     * @return : 변환된 CharacterSelectResDto
     */
    CharacterSelectResDto toSelectResDto(Long selectedCharacterId, Long deselectedCharacterId);

    /**
     * CharacterVrm Entity -> CharacterVrmResDto 변환
     * @param characterVrm : 변환할 CharacterVrm Entity
     * @return : 변환된 CharacterVrmResDto
     */
    @Mapping(target = "characterVrmId", source = "characterVrm.id")
    CharacterVrmResDto toCharacterVrmResDto(CharacterVrm characterVrm);
}
