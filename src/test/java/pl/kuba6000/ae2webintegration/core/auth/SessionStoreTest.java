package pl.kuba6000.ae2webintegration.core.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.kuba6000.ae2webintegration.core.WebPrincipal;

class SessionStoreTest {

    @TempDir
    Path directory;

    @Test
    void malformedSessionsArePreservedAndCannotAuthenticate() throws Exception {
        Path file = directory.resolve("sessions.json");
        Files.write(file, "not-json".getBytes(StandardCharsets.UTF_8));
        Map<String, AuthSession> sessions = new ConcurrentHashMap<>();
        SessionStore store = new SessionStore(file.toFile(), sessions);
        assertThrows(IOException.class, () -> store.load(0, principal -> "password"));
        assertTrue(sessions.isEmpty());
        assertFalse(Files.exists(file));
        assertEquals(
            "not-json",
            new String(Files.readAllBytes(directory.resolve("sessions.json.bad")), StandardCharsets.UTF_8));
        store.save(0);
        store.load(0, principal -> "password");
        assertTrue(sessions.isEmpty());
    }

    @Test
    void failedLoginWriteIsRetriedAfterTheFilesystemRecovers() throws Exception {
        Path file = directory.resolve("sessions.json");
        Path temporary = directory.resolve("sessions.json.tmp");
        Files.createDirectory(temporary);
        Path obstruction = temporary.resolve("blocked");
        Files.write(obstruction, new byte[] { 1 });
        Map<String, AuthSession> sessions = new ConcurrentHashMap<>();
        String hash = SessionStore.hashToken("token");
        sessions.put(hash, AuthSession.create(WebPrincipal.admin(), true, 0, "password"));
        SessionStore store = new SessionStore(file.toFile(), sessions);
        assertThrows(IOException.class, () -> store.save(0));
        Files.delete(obstruction);
        Files.delete(temporary);
        store.saveIfNeeded(1);
        Map<String, AuthSession> restored = new ConcurrentHashMap<>();
        new SessionStore(file.toFile(), restored).load(2, principal -> "password");
        assertTrue(restored.containsKey(hash));
    }

    @Test
    void failedRevocationWriteRemovesStaleTokensInsteadOfRestoringThem() throws Exception {
        Path file = directory.resolve("sessions.json");
        Map<String, AuthSession> sessions = new ConcurrentHashMap<>();
        sessions.put(SessionStore.hashToken("token"), AuthSession.create(WebPrincipal.admin(), true, 0, "password"));
        SessionStore store = new SessionStore(file.toFile(), sessions);
        store.save(0);
        Files.createDirectory(directory.resolve("sessions.json.tmp"));
        sessions.clear();
        store.saveRevocation(1);
        assertFalse(Files.exists(file));
        store.load(2, principal -> "password");
        assertTrue(sessions.isEmpty());
    }

    @Test
    void remembersOnlyPersistentTokensAndRejectsChangedCredentials() throws Exception {
        Path file = directory.resolve("sessions.json");
        Map<String, AuthSession> sessions = new ConcurrentHashMap<>();
        sessions.put(
            SessionStore.hashToken("remembered-secret"),
            AuthSession.create(WebPrincipal.admin(), true, 0, "password"));
        sessions.put(
            SessionStore.hashToken("temporary-secret"),
            AuthSession.create(WebPrincipal.admin(), false, 0, "password"));
        SessionStore store = new SessionStore(file.toFile(), sessions);
        store.save(1);
        String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertFalse(json.contains("remembered-secret"));
        assertFalse(json.contains("temporary-secret"));
        assertFalse(json.contains("password"));
        Map<String, AuthSession> restored = new ConcurrentHashMap<>();
        new SessionStore(file.toFile(), restored).load(2, principal -> "password");
        assertTrue(
            restored.get(SessionStore.hashToken("remembered-secret"))
                .isValid(2));
        assertEquals(1, restored.size());
        restored.clear();
        new SessionStore(file.toFile(), restored).load(2, principal -> "different");
        assertTrue(restored.isEmpty());
    }

    @Test
    void expiredRememberedSessionCannotReturnAfterRestart() throws Exception {
        Path file = directory.resolve("sessions.json");
        Map<String, AuthSession> sessions = new ConcurrentHashMap<>();
        sessions.put(SessionStore.hashToken("token"), AuthSession.create(WebPrincipal.admin(), true, 0, "password"));
        new SessionStore(file.toFile(), sessions).save(0);
        sessions.clear();
        new SessionStore(file.toFile(), sessions).load(TimeUnit.DAYS.toMillis(7), principal -> "password");
        assertTrue(sessions.isEmpty());
    }
}
