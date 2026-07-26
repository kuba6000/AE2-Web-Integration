package pl.kuba6000.ae2webintegration.core;

import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.io.Files;
import com.google.gson.Gson;

import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;

public class CoreData {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration");

    private static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * Only ever assigned by {@link #loadData()} at mod init, before any HTTP thread exists, so today it is
     * already safely published. Marked volatile so that stays true if data reloading is ever added to
     * /reload, which would write it from the server thread while requests are being served.
     */
    static volatile CoreData instance = new CoreData();

    /**
     * Resolved per call rather than once at class init: the config directory is only known after
     * {@link Config#init(File)}, so capturing it in a static initializer binds whatever happened to be set
     * when this class was first touched.
     */
    private static File dataFile() {
        return Config.getConfigFile("webdata.json");
    }

    /** 0 means a file written before versioning existed. */
    private int schemaVersion;

    // Written from the server thread by setPassword (the in-game /ae2webintegration auth command) and read
    // from HTTP worker threads during login, so plain HashMaps would offer no visibility guarantee at all.
    private final ConcurrentHashMap<UUID, Integer> UUIDToId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, UUID> IdToUUID = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> passwords = new ConcurrentHashMap<>();

    public static int getPlayerId(String name) {
        if (name == null || name.isEmpty()) {
            return -1;
        }
        UUID playerUuid = AE2Controller.serverPlatform.getRegisteredPlayerUUID(name);
        if (playerUuid == null) {
            return -1;
        }
        Integer id = instance.UUIDToId.get(playerUuid);
        if (id != null) {
            return id;
        }
        return -1;
    }

    public static boolean verifyPassword(int playerId, String password) {
        UUID id = instance.IdToUUID.get(playerId);
        if (id == null) {
            LOG.warn("Player ID " + playerId + " not found in IdToUUID map.");
            return false;
        }
        // One lookup, not containsKey-then-get: clearing a password is a remove, so the entry can vanish
        // between the two and hand null to the validator.
        String stored = instance.passwords.get(id);
        if (stored == null) {
            return false;
        }
        try {
            return PasswordHelper.validatePassword(password, stored);
        } catch (Exception e) {
            LOG.error("Password verification failed for player ID: " + playerId, e);
            return false;
        }
    }

    public static boolean setPassword(PlayerIdentity player, String passwordHash) {
        UUID playerUuid = player.uuid;
        if (passwordHash == null || passwordHash.isEmpty()) {
            instance.passwords.remove(playerUuid);
            saveChanges();
            return true;
        }

        try {
            int playerId = AE2Controller.AE2Interface.web$getPlayerData()
                .web$getPlayerId(player);
            if (playerId < 0) {
                LOG.error("Could not resolve AE2 player ID for UUID: " + playerUuid);
                return false;
            }

            instance.passwords.put(playerUuid, passwordHash);
            instance.UUIDToId.put(playerUuid, playerId);
            instance.IdToUUID.put(playerId, playerUuid);
            saveChanges();
            return true;
        } catch (Exception e) {
            LOG.error("Failed to resolve AE2 player ID for UUID: " + playerUuid, e);
            return false;
        }
    }

    private static void saveChanges() {
        CoreData data = instance;
        data.schemaVersion = CURRENT_SCHEMA_VERSION;
        try {
            GSONUtils.writeAtomically(dataFile(), data);
        } catch (Exception e) {
            LOG.error("Failed to save web data", e);
        }
    }

    public static void loadData() {
        Gson gson = GSONUtils.GSON_BUILDER.create();
        File file = dataFile();
        if (!file.exists()) {
            LOG.info("Web data file not found, creating a new one.");
            saveChanges();
            return;
        }
        try (Reader reader = Files.newReader(file, StandardCharsets.UTF_8)) {
            CoreData loaded = gson.fromJson(reader, CoreData.class);
            if (loaded == null) {
                // An empty or null-valued file deserializes to null without throwing, so this is not
                // reachable from the catch below.
                LOG.error("Web data file is empty or malformed, keeping the accounts already in memory");
                return;
            }
            if (loaded.schemaVersion > CURRENT_SCHEMA_VERSION) {
                LOG.warn(
                    "Web data file was written by a newer version (schema " + loaded.schemaVersion
                        + "), reading it as schema "
                        + CURRENT_SCHEMA_VERSION);
            }
            instance = loaded;
        } catch (Exception e) {
            // Deliberately no clear-and-save here: a failed read must not persist the loss of every
            // account. Leave the file alone so it can be inspected or restored.
            LOG.error("Failed to load web data from file: " + file.getAbsolutePath(), e);
        }
    }

}
