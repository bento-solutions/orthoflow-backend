package com.orthoflow.auth.application.service;

import com.orthoflow.auth.application.port.AuthorityResolver;
import com.orthoflow.auth.domain.model.Permission;
import com.orthoflow.auth.domain.model.UserRole;
import com.orthoflow.common.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which roles hold which permissions, per clinic. ADMIN always holds all of
 * them and is not editable; a DOCTOR or ASSISTANT holds the code-defined
 * defaults until an admin customises it, after which the stored set is
 * authoritative — even when it is empty.
 *
 * <p>Resolved on every request by {@code JwtAuthFilter}, so lookups are cached
 * for a few seconds. Edits clear the cache on this instance immediately, and
 * another instance catches up within the TTL.
 */
@Service
@RequiredArgsConstructor
public class RolePermissionService implements AuthorityResolver {

    private static final long CACHE_TTL_MILLIS = 5_000;

    private record Key(UUID practiceId, UserRole role) {
    }

    private record Cached(Set<Permission> permissions, long loadedAt) {
    }

    private final JdbcTemplate jdbc;
    private final Map<Key, Cached> cache = new ConcurrentHashMap<>();

    @Override
    public Set<Permission> permissionsFor(UUID practiceId, UserRole role) {
        if (role == UserRole.ADMIN) {
            return EnumSet.allOf(Permission.class);
        }
        Key key = new Key(practiceId, role);
        Cached cached = cache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.loadedAt() < CACHE_TTL_MILLIS) {
            return cached.permissions();
        }
        Set<Permission> loaded = load(practiceId, role);
        cache.put(key, new Cached(loaded, now));
        return loaded;
    }

    /** The full matrix for the settings screen: every role, what it holds, and whether it is customised. */
    @Transactional(readOnly = true)
    public Map<UserRole, Set<Permission>> matrix(UUID practiceId) {
        Map<UserRole, Set<Permission>> result = new EnumMap<>(UserRole.class);
        for (UserRole role : UserRole.values()) {
            result.put(role, permissionsFor(practiceId, role));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Set<UserRole> customisedRoles(UUID practiceId) {
        Set<UserRole> roles = EnumSet.noneOf(UserRole.class);
        jdbc.query("SELECT role FROM role_permission_sets WHERE practice_id = ?",
                rs -> {
                    roles.add(UserRole.valueOf(rs.getString(1)));
                }, practiceId);
        return roles;
    }

    @Transactional
    public void replace(UUID practiceId, UserRole role, Set<Permission> permissions, UUID actorId) {
        if (role == UserRole.ADMIN) {
            throw new ValidationException("The administrator role always holds every permission and cannot be edited");
        }
        jdbc.update("""
                INSERT INTO role_permission_sets (practice_id, role, updated_at, updated_by)
                VALUES (?, ?, NOW(), ?)
                ON CONFLICT (practice_id, role) DO UPDATE SET updated_at = NOW(), updated_by = EXCLUDED.updated_by
                """, practiceId, role.name(), actorId);
        jdbc.update("DELETE FROM role_permissions WHERE practice_id = ? AND role = ?", practiceId, role.name());
        for (Permission permission : permissions) {
            jdbc.update("INSERT INTO role_permissions (practice_id, role, permission) VALUES (?, ?, ?)",
                    practiceId, role.name(), permission.name());
        }
        cache.remove(new Key(practiceId, role));
    }

    /** Drops a role's customisation so it follows the code-defined defaults again. */
    @Transactional
    public void reset(UUID practiceId, UserRole role) {
        jdbc.update("DELETE FROM role_permission_sets WHERE practice_id = ? AND role = ?", practiceId, role.name());
        cache.remove(new Key(practiceId, role));
    }

    private Set<Permission> load(UUID practiceId, UserRole role) {
        Integer customised = jdbc.queryForObject(
                "SELECT COUNT(*) FROM role_permission_sets WHERE practice_id = ? AND role = ?",
                Integer.class, practiceId, role.name());
        if (customised == null || customised == 0) {
            return Permission.defaultsFor(role);
        }
        Set<Permission> stored = EnumSet.noneOf(Permission.class);
        jdbc.query("SELECT permission FROM role_permissions WHERE practice_id = ? AND role = ?",
                rs -> {
                    try {
                        stored.add(Permission.valueOf(rs.getString(1)));
                    } catch (IllegalArgumentException retired) {
                        // A permission removed from the code in a later release: ignore the stale row.
                    }
                }, practiceId, role.name());
        return stored;
    }
}
