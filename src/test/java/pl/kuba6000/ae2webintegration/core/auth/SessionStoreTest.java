package pl.kuba6000.ae2webintegration.core.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.WebPrincipal;

class SessionStoreTest {

    @TempDir
    Path directory;

    @Test
    void discardedRememberedTokensCannotReturnWhenPrunedFileCannotBeWritten() throws Exception {
        Path file = directory.resolve("sessions.json");
        Map<String, AuthSession> sessions = new ConcurrentHashMap<>();
        sessions.put(SessionStore.hashToken("template"), AuthSession.create(WebPrincipal.admin(), true, 0, "password"));
        new SessionStore(file.toFile(), sessions).save(0);
        JsonObject document = new Gson()
            .fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), JsonObject.class);
        JsonObject template = document.getAsJsonArray("sessions")
            .get(0)
            .getAsJsonObject();
        JsonArray entries = new JsonArray();
        for (int i = 0; i < 21; i++) {
            JsonObject entry = new Gson().fromJson(template.toString(), JsonObject.class);
            entry.addProperty("hash", SessionStore.hashToken("token-" + i));
            entry.addProperty("absolute", TimeUnit.DAYS.toMillis(60) + i);
            entries.add(entry);
        }
        document.add("sessions", entries);
        Files.write(
            file,
            document.toString()
                .getBytes(StandardCharsets.UTF_8));
        sessions.clear();
        SessionStore store = new SessionStore(file.toFile(), sessions);
        store.load(1000, principal -> "password");
        assertEquals(20, sessions.size());
        assertFalse(sessions.containsKey(SessionStore.hashToken("token-0")));
        Files.createDirectory(directory.resolve("sessions.json.tmp"));
        assertDoesNotThrow(() -> store.saveIfNeeded(1000));
        assertFalse(Files.exists(file), "failed pruning must remove the file containing evicted credentials");
        Map<String, AuthSession> restored = new ConcurrentHashMap<>();
        new SessionStore(file.toFile(), restored).load(1000, principal -> "password");
        assertTrue(restored.isEmpty());
    }

    @Test
    void clockRollbackSkipsOnlyFutureDatedEntryWithoutQuarantiningTheFile() throws Exception {
        Path file = directory.resolve("sessions.json");
        Map<String, AuthSession> sessions = new ConcurrentHashMap<>();
        String valid = SessionStore.hashToken("valid");
        String future = SessionStore.hashToken("future");
        sessions.put(valid, AuthSession.create(WebPrincipal.admin(), true, 0, "password"));
        sessions.put(future, AuthSession.create(WebPrincipal.admin(), true, 5000, "password"));
        SessionStore store = new SessionStore(file.toFile(), sessions);
        store.save(5000);
        sessions.clear();
        assertDoesNotThrow(() -> store.load(0, principal -> "password"));
        assertEquals(1, sessions.size());
        assertTrue(sessions.containsKey(valid));
        assertFalse(sessions.containsKey(future));
        assertTrue(Files.exists(file));
        assertFalse(Files.exists(directory.resolve("sessions.json.bad")));
        store.saveIfNeeded(0);
        JsonObject saved = new Gson()
            .fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), JsonObject.class);
        assertEquals(
            1,
            saved.getAsJsonArray("sessions")
                .size());
    }

    @Test
    void oversizedFileRestoresOnlyNewestRememberedSessionsPerAccountBeforeCredentialResolution() throws Exception {
        Path file = directory.resolve("sessions.json");
        Map<String, AuthSession> sessions = new ConcurrentHashMap<>();
        sessions.put(SessionStore.hashToken("template"), AuthSession.create(WebPrincipal.admin(), true, 0, "password"));
        new SessionStore(file.toFile(), sessions).save(0);
        JsonObject document = new Gson()
            .fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), JsonObject.class);
        JsonObject template = document.getAsJsonArray("sessions")
            .get(0)
            .getAsJsonObject();
        JsonArray entries = new JsonArray();
        for (int i = 0; i < 24; i++) {
            JsonObject entry = new Gson().fromJson(template.toString(), JsonObject.class);
            entry.addProperty("hash", SessionStore.hashToken("token-" + i));
            entry.addProperty("absolute", TimeUnit.DAYS.toMillis(60) + i);
            if (i >= 22) entry.addProperty("uuid", "00000000-0000-0000-0000-000000000001");
            entries.add(entry);
        }
        document.add("sessions", entries);
        Files.write(
            file,
            document.toString()
                .getBytes(StandardCharsets.UTF_8));
        sessions.clear();
        AtomicInteger credentialResolutions = new AtomicInteger();
        SessionStore store = new SessionStore(file.toFile(), sessions);
        store.load(1000, principal -> {
            credentialResolutions.incrementAndGet();
            return "password";
        });
        assertEquals(22, sessions.size());
        assertFalse(sessions.containsKey(SessionStore.hashToken("token-0")));
        assertFalse(sessions.containsKey(SessionStore.hashToken("token-1")));
        assertTrue(sessions.containsKey(SessionStore.hashToken("token-2")));
        assertTrue(sessions.containsKey(SessionStore.hashToken("token-23")));
        assertEquals(22, credentialResolutions.get(), "discarded entries must not perform credential work");
        store.saveIfNeeded(1000);
        JsonObject saved = new Gson()
            .fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), JsonObject.class);
        assertEquals(
            22,
            saved.getAsJsonArray("sessions")
                .size());
    }

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
