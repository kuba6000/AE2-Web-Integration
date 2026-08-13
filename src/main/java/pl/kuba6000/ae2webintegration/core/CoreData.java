package pl.kuba6000.ae2webintegration.core;

import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
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

    private static final int CURRENT_SCHEMA_VERSION = 2;

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
    private final ConcurrentHashMap<UUID, String> usernames = new ConcurrentHashMap<>();

    // Derived from usernames after load. Persisting both directions would create two sources of truth.
    private transient ConcurrentHashMap<String, UUID> usernameToUUID = new ConcurrentHashMap<>();

    public static final class Account {

        private final UUID uuid;
        private final int playerId;
        private final String username;

        private Account(UUID uuid, int playerId, String username) {
            this.uuid = uuid;
            this.playerId = playerId;
            this.username = username;
        }

        public int getPlayerId() {
            return playerId;
        }

        public String getUsername() {
            return username;
        }
    }

    public static Account getAccount(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        CoreData data = instance;
        String normalizedName = normalizeUsername(name);
        UUID playerUuid = data.usernameToUUID.get(normalizedName);
        if (playerUuid == null) {
            return null;
        }
        Integer playerId = data.UUIDToId.get(playerUuid);
        String username = data.usernames.get(playerUuid);
        if (playerId == null || username == null || !normalizedName.equals(normalizeUsername(username))) {
            return null;
        }
        return new Account(playerUuid, playerId, username);
    }

    public static int getPlayerId(String name) {
        Account account = getAccount(name);
        return account != null ? account.playerId : -1;
    }

    public static String getPlayerName(int playerId) {
        UUID playerUuid = instance.IdToUUID.get(playerId);
        return playerUuid != null ? instance.usernames.get(playerUuid) : null;
    }

    public static boolean verifyPassword(int playerId, String password) {
        UUID id = instance.IdToUUID.get(playerId);
        if (id == null) {
            LOG.warn("Player ID " + playerId + " not found in IdToUUID map.");
            return false;
        }
        return verifyPassword(id, password);
    }

    public static boolean verifyPassword(Account account, String password) {
        return account != null && verifyPassword(account.uuid, password);
    }

    private static boolean verifyPassword(UUID playerUuid, String password) {
        // One lookup, not containsKey-then-get: clearing a password is a remove, so the entry can vanish
        // between the two and hand null to the validator.
        String stored = instance.passwords.get(playerUuid);
        if (stored == null) {
            return false;
        }
        try {
            return PasswordHelper.validatePassword(password, stored);
        } catch (Exception e) {
            LOG.error("Password verification failed for player UUID: " + playerUuid, e);
            return false;
        }
    }

    /**
     * Records the canonical name supplied by an online player identity. Legacy accounts did not persist
     * names, and existing accounts need the same update when their owner renames. The UUID remains the
     * account identity; the name is only a case-insensitive login index.
     */
    public static void observePlayer(PlayerIdentity player) {
        if (player == null || player.uuid == null || player.name == null || player.name.isEmpty()) {
            return;
        }
        CoreData data = instance;
        if (!data.passwords.containsKey(player.uuid) || !data.UUIDToId.containsKey(player.uuid)) {
            return;
        }
        if (data.recordUsername(player.uuid, player.name)) {
            saveChanges();
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
            instance.recordUsername(playerUuid, player.name);
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
            loaded.rebuildUsernameIndex();
            instance = loaded;
        } catch (Exception e) {
            // Deliberately no clear-and-save here: a failed read must not persist the loss of every
            // account. Leave the file alone so it can be inspected or restored.
            LOG.error("Failed to load web data from file: " + file.getAbsolutePath(), e);
        }
    }

    private static String normalizeUsername(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    /**
     * Updates both sides of the login-name index. A name belongs to the UUID most recently confirmed by
     * an online player identity, so reusing a renamed player's old name must also remove that stale name
     * from the previous account.
     */
    private boolean recordUsername(UUID playerUuid, String username) {
        String previousName = usernames.put(playerUuid, username);
        if (previousName != null) {
            usernameToUUID.remove(normalizeUsername(previousName), playerUuid);
        }

        String normalizedUsername = normalizeUsername(username);
        UUID previousOwner = usernameToUUID.put(normalizedUsername, playerUuid);
        if (previousOwner != null && !previousOwner.equals(playerUuid)) {
            String previousOwnerName = usernames.get(previousOwner);
            if (previousOwnerName != null && normalizedUsername.equals(normalizeUsername(previousOwnerName))) {
                usernames.remove(previousOwner, previousOwnerName);
            }
        }
        return !username.equals(previousName);
    }

    private void rebuildUsernameIndex() {
        usernameToUUID = new ConcurrentHashMap<>();
        for (Map.Entry<UUID, String> entry : usernames.entrySet()) {
            String username = entry.getValue();
            if (username != null && !username.isEmpty()) {
                usernameToUUID.put(normalizeUsername(username), entry.getKey());
            }
        }
    }

}
