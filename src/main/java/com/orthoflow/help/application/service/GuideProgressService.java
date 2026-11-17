package com.orthoflow.help.application.service;

import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.help.application.dto.GuideDtos.GuideKey;
import com.orthoflow.help.application.dto.GuideDtos.GuideProgress;
import com.orthoflow.help.application.dto.GuideDtos.GuideStatus;
import com.orthoflow.help.application.dto.GuideDtos.SaveGuideProgress;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Where a user is in the first-login guide. Always the signed-in user's own rows: the caller
 * passes the id from the token, never one from the request.
 *
 * <p>Saving is idempotent, and a chapter never goes back down ({@link GuideStatus} is ordered)
 * unless the content changed ({@code version} went up) or the user asked to see it again
 * ({@link #reset}). Every "later" counts, so the guide can stop asking after a while.
 */
@Service
@RequiredArgsConstructor
public class GuideProgressService {

    private static final RowMapper<GuideProgress> ROW = (rs, n) -> new GuideProgress(
            GuideKey.valueOf(rs.getString("guide_key")),
            GuideStatus.valueOf(rs.getString("status")),
            rs.getInt("version"),
            rs.getInt("snooze_count"),
            rs.getString("last_step"),
            rs.getObject("updated_at", OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    /** The text of a path segment as a guide key; anything else is a bad request, not a server error. */
    public static GuideKey key(String raw) {
        try {
            return GuideKey.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ValidationException("Unknown guide: " + raw);
        }
    }

    @Transactional(readOnly = true)
    public List<GuideProgress> mine(UUID userId) {
        return jdbc.query("SELECT * FROM user_guide_progress WHERE user_id = ? ORDER BY guide_key", ROW, userId);
    }

    @Transactional
    public GuideProgress save(UUID userId, GuideKey key, SaveGuideProgress body) {
        // Row lock, so two tabs saving at once cannot both read "no row" and both count a snooze once.
        GuideProgress current = jdbc.query("SELECT * FROM user_guide_progress WHERE user_id = ? AND guide_key = ? FOR UPDATE",
                ROW, userId, key.name()).stream().findFirst().orElse(null);
        int snoozes = body.status() == GuideStatus.SNOOZED ? 1 : 0;
        if (current == null) {
            jdbc.update("""
                    INSERT INTO user_guide_progress (user_id, guide_key, status, version, snooze_count, last_step, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, NOW())
                    ON CONFLICT (user_id, guide_key) DO NOTHING
                    """, userId, key.name(), body.status().name(), body.version(), snoozes, body.lastStep());
            return find(userId, key);
        }
        boolean newerContent = body.version() > current.version();
        boolean goesDown = body.status().compareTo(current.status()) < 0;
        if (goesDown && !newerContent) {
            return current; // already further along: saying it again changes nothing
        }
        boolean sameAgain = body.status() == current.status() && body.version() == current.version()
                && body.status() != GuideStatus.SNOOZED;
        if (sameAgain && (body.lastStep() == null || body.lastStep().equals(current.lastStep()))) {
            return current;
        }
        jdbc.update("""
                UPDATE user_guide_progress
                   SET status = ?, version = GREATEST(version, ?), snooze_count = snooze_count + ?,
                       last_step = COALESCE(?, last_step), updated_at = NOW()
                 WHERE user_id = ? AND guide_key = ?
                """, body.status().name(), body.version(), snoozes, body.lastStep(), userId, key.name());
        return find(userId, key);
    }

    /** Forgets a chapter, so it is offered again as if never seen. */
    @Transactional
    public void reset(UUID userId, GuideKey key) {
        jdbc.update("DELETE FROM user_guide_progress WHERE user_id = ? AND guide_key = ?", userId, key.name());
    }

    private GuideProgress find(UUID userId, GuideKey key) {
        return jdbc.queryForObject("SELECT * FROM user_guide_progress WHERE user_id = ? AND guide_key = ?", ROW, userId, key.name());
    }
}
