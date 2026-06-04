-- ============================================
-- Migration: Remove VoiceType persistence
-- Date: 2026-06-04
-- Description: CharacterPersona의 PresetType이 Gemini 음성명을 직접 보유하게 되면서
--              VoiceType 엔티티/테이블이 불필요해졌습니다.
--              character 테이블에서 voice_type_id 컬럼과 FK를 제거하고
--              voice_type 테이블을 삭제합니다.
--
-- 주의: 반드시 모든 구버전 애플리케이션 인스턴스를 종료한 후 실행하세요.
--       character_persona 테이블은 변경 불필요 (preset_type 컬럼 기존 유지)
-- ============================================

START TRANSACTION;

-- 1. character 테이블에서 voice_type_id FK 제약조건명을 동적으로 찾아 DROP
SET @fk_name := (
    SELECT kcu.CONSTRAINT_NAME
    FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
    WHERE kcu.TABLE_SCHEMA = DATABASE()
      AND kcu.TABLE_NAME = 'character'
      AND kcu.COLUMN_NAME = 'voice_type_id'
      AND kcu.REFERENCED_TABLE_NAME = 'voice_type'
    LIMIT 1
);

SET @drop_fk_sql := IF(
    @fk_name IS NOT NULL,
    CONCAT('ALTER TABLE `character` DROP FOREIGN KEY `', @fk_name, '`'),
    'SELECT 1'
);

PREPARE stmt FROM @drop_fk_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. character 테이블에서 voice_type_id 컬럼 제거
ALTER TABLE `character`
    DROP COLUMN `voice_type_id`;

-- 3. voice_type 테이블 제거
DROP TABLE IF EXISTS `voice_type`;

COMMIT;
