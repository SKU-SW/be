package com.example.sku_sw.domain.broadcast.repository;

import com.example.sku_sw.domain.broadcast.entity.BroadcastDialogue;
import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class BroadcastDialogueBulkRepository {
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public void saveAll(List<BroadcastDialogue> broadcastDialogues) {
        String sql = "INSERT INTO broadcast_dialogue (cursor_id, subject, content, created_at, broadcast_id) " +
                "VALUES (?, ?, ?, ?, ?)";

        jdbcTemplate.batchUpdate(sql, broadcastDialogues, broadcastDialogues.size(),
                (PreparedStatement ps, BroadcastDialogue broadcastDialogue) -> {
                    ps.setLong(1, broadcastDialogue.getCursorId());
                    ps.setString(2, broadcastDialogue.getSubject().toString());
                    ps.setString(3, broadcastDialogue.getContent());
                    ps.setObject(4, broadcastDialogue.getCreatedAt());
                    ps.setLong(5, broadcastDialogue.getBroadcast().getId());
                });
    }

}
