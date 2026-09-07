package com.example.sku_sw.domain.user.repository;

import com.example.sku_sw.domain.user.entity.User;
import com.example.sku_sw.domain.user.enums.RegisterType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(locations = "classpath:application-test.yml")
class UserRepositoryH2Test {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("H2에서 사용자를 저장하고 이메일로 조회한다")
    void saveAndFindByEmail_success() throws SQLException {
        // given
        User user = User.createUser(
                "테스트 사용자",
                "h2-test@example.com",
                "hashed-password",
                RegisterType.EMAIL
        );

        // when
        User savedUser = userRepository.saveAndFlush(user);
        User foundUser = userRepository.findByEmail(savedUser.getEmail()).orElseThrow();

        // then
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("H2");
        }
        assertThat(savedUser.getId()).isNotNull();
        assertThat(foundUser.getId()).isEqualTo(savedUser.getId());
        assertThat(foundUser.getName()).isEqualTo("테스트 사용자");
    }
}
