package com.example.sku_sw.domain.character.repository;

import com.example.sku_sw.domain.character.entity.CharacterImageDetail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CharacterImageDetailRepository extends JpaRepository<CharacterImageDetail, Long> {

    /**
     * characterImageId로 모든 이미지 상세 목록을 감정 오름차순으로 조회하는 함수
     * @param characterImageId : 캐릭터 이미지 ID
     * @return : 이미지 상세 목록
     */
    List<CharacterImageDetail> findAllByCharacterImageIdOrderByEmotionAsc(Long characterImageId);

    /**
     * characterImageId로 첫 번째 이미지 상세를 id ASC로 조회하는 함수
     * @param characterImageId : 캐릭터 이미지 ID
     * @return : Optional<CharacterImageDetail>
     */
    Optional<CharacterImageDetail> findFirstByCharacterImageIdOrderByIdAsc(Long characterImageId);
}
