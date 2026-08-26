package com.example.sku_sw.domain.user.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UserTest {

    @Test
    @DisplayName("선택한 캐릭터 ID와 요청 캐릭터 ID가 같으면 true를 반환한다")
    void isSelectedCharacter_선택한_캐릭터와_일치_true_반환() {
        // given
        Long selectedCharacterId = 1L;
        User user = User.builder()
                .selectedCharacterId(selectedCharacterId)
                .build();

        // when
        boolean result = user.isSelectedCharacter(selectedCharacterId);

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("선택한 캐릭터가 없으면 false를 반환한다")
    void isSelectedCharacter_선택한_캐릭터_없음_false_반환() {
        // given
        User user = User.builder().build();

        // when
        boolean result = user.isSelectedCharacter(1L);

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("선택한 캐릭터 ID와 요청 캐릭터 ID가 다르면 false를 반환한다")
    void isSelectedCharacter_선택한_캐릭터와_불일치_false_반환() {
        // given
        User user = User.builder()
                .selectedCharacterId(1L)
                .build();

        // when
        boolean result = user.isSelectedCharacter(2L);

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("요청 캐릭터 ID가 null이면 false를 반환한다")
    void isSelectedCharacter_요청_캐릭터_ID_null_false_반환() {
        // given
        User user = User.builder()
                .selectedCharacterId(1L)
                .build();

        // when
        boolean result = user.isSelectedCharacter(null);

        // then
        assertThat(result).isFalse();
    }
}
