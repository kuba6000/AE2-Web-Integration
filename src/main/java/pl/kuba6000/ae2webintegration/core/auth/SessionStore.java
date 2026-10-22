package pl.kuba6000.ae2webintegration.core.auth;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;
import com.google.common.io.BaseEncoding;

import pl.kuba6000.ae2webintegration.core.PasswordHelper;
import pl.kuba6000.ae2webintegration.core.WebPrincipal;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;

/** Serialized writes snapshot current live sessions, never queued stale copies. */
public final class SessionStore {

    private final File file;
    private final Map<String, AuthSession> sessions;
    private boolean closed;
    private boolean dirty;
    private boolean revocationPending;

    public SessionStore(@NotNull File file, @NotNull Map<String, AuthSession> sessions) {
        this.file = file;
        this.sessions = sessions;
    }

    @Desugar
    private record Document(int version, List<Entry> sessions) {}

    @Desugar
    private record Entry(String hash, @Nullable UUID uuid, String username, long expires, long absolute,
        String credentialProof) {}

    public static @NotNull String hashToken(@NotNull String token) {
        try {
            return BaseEncoding.base16()
                .lowerCase()
                .encode(
                    MessageDigest.getInstance("SHA-256")
                        .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Loads only sessions still authorized by the current credentials, including offline edits. */
    public synchronized void load(long now, @NotNull Function<WebPrincipal, String> credentials) throws IOException {
        if (!file.exists()) return;
        List<Map.Entry<String, AuthSession>> restored = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            Document document = GSONUtils.GSON_BUILDER.create()
                .fromJson(reader, Document.class);
            if (document == null || document.version() != 1 || document.sessions() == null)
                throw new IllegalArgumentException("Invalid session file");
            for (Entry entry : document.sessions()) {
                if (entry == null || entry.hash() == null
                    || !entry.hash()
                        .matches("[0-9a-f]{64}")
                    || entry.username() == null
                    || entry.credentialProof() == null
                    || !entry.credentialProof()
                        .matches("65536:[0-9a-f]{32}:[0-9a-f]{128}")
                    || entry.expires() > entry.absolute()
                    || entry.absolute() - now > AuthSession.REMEMBERED_CAP_MILLIS)
                    throw new IllegalArgumentException("Invalid persisted session");
                if (entry.expires() <= now) continue;
                WebPrincipal principal = entry.uuid() == null ? WebPrincipal.admin()
                    : WebPrincipal.forPlayer(new PlayerIdentity(entry.uuid(), entry.username()));
                String credential = credentials.apply(principal);
                if (credential == null || !PasswordHelper.validatePassword(credential, entry.credentialProof()))
                    continue;
                restored.add(
                    new SimpleImmutableEntry<>(
                        entry.hash(),
                        new AuthSession(
                            principal,
                            entry.expires(),
                            entry.absolute(),
                            true,
                            credential,
                            entry.credentialProof())));
            }
        } catch (IOException | RuntimeException | GeneralSecurityException failure) {
            File backup = new File(file + ".bad");
            if (backup.exists()) backup = new File(file + ".bad." + UUID.randomUUID());
            try {
                Files.move(file.toPath(), backup.toPath());
            } catch (IOException backupFailure) {
                closed = true; // Never overwrite an unreadable file that could not be preserved.
                failure.addSuppressed(backupFailure);
            }
            throw new IOException("Could not read remembered sessions; backup: " + backup, failure);
        }
        for (Map.Entry<String, AuthSession> entry : restored) sessions.put(entry.getKey(), entry.getValue());
    }

    public synchronized void save(long now) throws IOException {
        if (closed) return;
        dirty = true;
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<String, AuthSession> entry : sessions.entrySet()) {
            AuthSession session = entry.getValue();
            if (!session.persistent() || !session.isValid(now)) continue;
            PlayerIdentity player = session.principal()
                .getPlayerIdentity();
            entries.add(
                new Entry(
                    entry.getKey(),
                    player == null ? null : player.uuid,
                    session.principal()
                        .getUsername(),
                    session.expiresAtMillis(),
                    session.absoluteExpiresAtMillis(),
                    session.credentialProof()));
        }
        GSONUtils.writeAtomically(file, new Document(1, entries));
        if (Files.getFileStore(file.toPath())
            .supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(
                file.toPath(),
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
        for (Entry entry : entries) {
            AuthSession session = sessions.get(entry.hash());
            if (session != null) session.saved(entry.expires());
        }
        dirty = false;
        revocationPending = false;
    }

    public synchronized void markChanged(boolean revocation) {
        dirty = true;
        revocationPending |= revocation;
    }

    /** Retries failed writes even when the last persisted login has been removed from memory. */
    public synchronized void saveIfNeeded(long now) throws IOException {
        if (!dirty || closed) return;
        if (revocationPending) saveRevocation(now);
        else save(now);
    }

    /** A failed security write must not leave a reusable logged-out token on disk. */
    public synchronized void saveRevocation(long now) throws IOException {
        markChanged(true);
        try {
            save(now);
        } catch (IOException | RuntimeException failure) {
            LogManager.getLogger("ae2webintegration")
                .warn("Session revocation could not be saved; removing persisted sessions", failure);
            try {
                Files.deleteIfExists(file.toPath());
                revocationPending = false;
            } catch (IOException deletionFailure) {
                failure.addSuppressed(deletionFailure);
                throw new IOException("Could not persist session revocation or remove stale sessions", failure);
            }
        }
    }

    public synchronized void close(long now) throws IOException {
        try {
            save(now);
        } finally {
            closed = true;
        }
    }
}
