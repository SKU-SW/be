package com.example.sku_sw.domain.user.repository;

import com.example.sku_sw.domain.user.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User,Long>{
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    /**
     * 비관적 쓰기 락으로 사용자 조회
     * - 캐릭터 선택/해제 시 동시성 제어를 위해 사용
     * @param id : 조회할 사용자 ID
     * @return : 조회된 User Entity (Optional)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdWithLock(@Param("id") Long id);
}
