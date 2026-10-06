package com.orthoflow.help.infrastructure;

import com.orthoflow.help.domain.model.HelpNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HelpNoteJpaRepository extends JpaRepository<HelpNote, UUID> {

    /** The built-in notes plus this clinic's own, every language. */
    @Query("SELECT n FROM HelpNote n WHERE n.practiceId IS NULL OR n.practiceId = :practiceId")
    List<HelpNote> findVisible(@Param("practiceId") UUID practiceId);

    Optional<HelpNote> findByPracticeIdAndPageKeyAndLang(UUID practiceId, String pageKey, String lang);
}
