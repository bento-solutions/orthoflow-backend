package com.orthoflow.messaging.infrastructure.persistence;

import com.orthoflow.messaging.domain.model.MessageChannel;
import com.orthoflow.messaging.domain.model.MessagePurpose;
import com.orthoflow.messaging.domain.model.MessageTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageTemplateJpaRepository extends JpaRepository<MessageTemplate, UUID> {

    List<MessageTemplate> findByPracticeIdOrderByPurposeAscChannelAscLanguageAsc(UUID practiceId);

    Optional<MessageTemplate> findByPracticeIdAndChannelAndPurposeAndLanguage(
            UUID practiceId, MessageChannel channel, MessagePurpose purpose, String language);
}
