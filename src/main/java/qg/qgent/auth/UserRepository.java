package qg.qgent.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record User(UUID id, String email, String displayName, String avatarUrl, String passwordHash,
            String status) {
    }

    public record Token(UUID id, UUID userId, Instant expiresAt) {
    }

    Optional<User> findByEmail(String email) {
        return jdbc.query(
                "select id,email,display_name,avatar_url,password_hash,status from users where email_normalized=?",
                this::user, email).stream().findFirst();
    }

    Optional<User> findById(UUID id) {
        return jdbc.query("select id,email,display_name,avatar_url,password_hash,status from users where id=?",
                this::user, bytes(id)).stream().findFirst();
    }

    void insert(User u) {
        jdbc.update(
                "insert into users(id,email,email_normalized,display_name,avatar_url,password_hash,password_algorithm,status) values(?,?,?,?,?,?, 'BCRYPT','ACTIVE')",
                bytes(u.id), u.email, u.email.toLowerCase(), u.displayName, u.avatarUrl, u.passwordHash);
    }

    void updateProfile(UUID id, String displayName, String avatarUrl) {
        jdbc.update(
                "update users set display_name=coalesce(?,display_name), avatar_url=coalesce(?,avatar_url) where id=?",
                displayName, avatarUrl, bytes(id));
    }

    void updatePassword(UUID id, String hash) {
        jdbc.update("update users set password_hash=?, password_algorithm='BCRYPT' where id=?", hash, bytes(id));
    }

    void insertRefresh(UUID id, UUID userId, byte[] hash, Instant expiresAt) {
        jdbc.update("insert into refresh_tokens(id,user_id,token_hash,expires_at) values(?,?,?,?)",
                bytes(id), bytes(userId), hash, java.sql.Timestamp.from(expiresAt));
    }

    Optional<Token> activeRefresh(byte[] hash) {
        return jdbc.query(
                "select id,user_id,expires_at from refresh_tokens where token_hash=? and revoked_at is null and expires_at>utc_timestamp(6) for update",
                (rs, n) -> new Token(uuid(rs.getBytes("id")), uuid(rs.getBytes("user_id")),
                        rs.getTimestamp("expires_at").toInstant()),
                hash)
                .stream().findFirst();
    }

    void revokeRefresh(UUID id) {
        jdbc.update("update refresh_tokens set revoked_at=utc_timestamp(6) where id=? and revoked_at is null",
                bytes(id));
    }

    void revokeAllRefresh(UUID userId) {
        jdbc.update("update refresh_tokens set revoked_at=utc_timestamp(6) where user_id=? and revoked_at is null",
                bytes(userId));
    }

    void insertReset(UUID id, UUID userId, byte[] hash, Instant expiresAt) {
        jdbc.update("insert into password_reset_tokens(id,user_id,token_hash,expires_at) values(?,?,?,?)",
                bytes(id), bytes(userId), hash, java.sql.Timestamp.from(expiresAt));
    }

    Optional<Token> activeReset(byte[] hash) {
        return jdbc.query(
                "select id,user_id,expires_at from password_reset_tokens where token_hash=? and used_at is null and expires_at>utc_timestamp(6) for update",
                (rs, n) -> new Token(uuid(rs.getBytes("id")), uuid(rs.getBytes("user_id")),
                        rs.getTimestamp("expires_at").toInstant()),
                hash)
                .stream().findFirst();
    }

    void useAllResets(UUID userId) {
        jdbc.update("update password_reset_tokens set used_at=utc_timestamp(6) where user_id=? and used_at is null",
                bytes(userId));
    }

    List<AuthDtos.TeamView> teams(UUID userId) {
        return jdbc.query(
                "select t.id,t.name,tm.role from team_members tm join teams t on t.id=tm.team_id where tm.user_id=? and t.status='ACTIVE'",
                (rs, n) -> new AuthDtos.TeamView(uuid(rs.getBytes("id")).toString(), rs.getString("name"),
                        rs.getString("role")),
                bytes(userId));
    }

    List<AuthDtos.ProjectView> projects(UUID userId) {
        return jdbc.query(
                "select p.id,p.team_id,p.name,pm.role,p.status from project_members pm join projects p on p.id=pm.project_id where pm.user_id=? and p.status='ACTIVE'",
                (rs, n) -> new AuthDtos.ProjectView(uuid(rs.getBytes("id")).toString(),
                        uuid(rs.getBytes("team_id")).toString(), rs.getString("name"), rs.getString("role"),
                        rs.getString("status")),
                bytes(userId));
    }

    private User user(ResultSet rs, int n) throws SQLException {
        return new User(uuid(rs.getBytes("id")), rs.getString("email"), rs.getString("display_name"),
                rs.getString("avatar_url"), rs.getString("password_hash"), rs.getString("status"));
    }

    static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits())
                .array();
    }

    static UUID uuid(byte[] value) {
        ByteBuffer b = ByteBuffer.wrap(value);
        return new UUID(b.getLong(), b.getLong());
    }
}
