package pl.kuba6000.ae2webintegration.core.config;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;
import com.google.common.io.Files;
import com.google.gson.Gson;

import pl.kuba6000.ae2webintegration.core.PasswordHelper;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.auth.AuthService;
import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;
import pl.kuba6000.ae2webintegration.core.utils.ReleaseManifest;

@SuppressWarnings("UnstableApiUsage")
public class CoreData {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration");

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

    // Written from the server thread by setPassword (the in-game /ae2webintegration auth command) and read
    // from HTTP worker threads during login, so plain HashMaps would offer no visibility guarantee at all.
    private final ConcurrentHashMap<UUID, String> passwords = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> usernames = new ConcurrentHashMap<>();

    // Derived from usernames after load. Persisting both directions would create two sources of truth.
    private transient ConcurrentHashMap<String, UUID> usernameToUUID = new ConcurrentHashMap<>();

    // All mutations, saves and suppression reads share the class lock, including account changes.
    private @Nullable SkippedUpdate skippedUpdate;

    @Desugar
    private record SkippedUpdate(@NotNull String minecraftVersion, @NotNull String loader, @NotNull String version,
        @NotNull ReleaseManifest.Channel channel, @NotNull String tag) {}

    /** Reads only committed suppression; a concurrent failed save must never hide a notice. */
    public static synchronized boolean isUpdateSkipped(@NotNull String minecraftVersion, @NotNull String loader,
        @NotNull ReleaseManifest.Release release) {
        SkippedUpdate skipped = instance.skippedUpdate;
        return skipped != null && skipped.minecraftVersion()
            .equals(minecraftVersion)
            && skipped.loader()
                .equals(loader)
            && release.isAtMost(skipped.version(), skipped.channel(), skipped.tag());
    }

    /** Persists the administrator's choice together with accounts, publishing it only after a successful save. */
    public static synchronized void skipUpdate(@NotNull String minecraftVersion, @NotNull String loader,
        @NotNull ReleaseManifest.Release release) throws IOException {
        if (isUpdateSkipped(minecraftVersion, loader, release)) return;
        CoreData data = instance;
        SkippedUpdate previous = data.skippedUpdate;
        data.skippedUpdate = new SkippedUpdate(minecraftVersion, loader, release.version, release.channel, release.tag);
        try {
            GSONUtils.writeAtomically(dataFile(), data);
        } catch (IOException | RuntimeException failure) {
            data.skippedUpdate = previous;
            throw failure;
        }
    }

    public static final class Account {

        private final PlayerIdentity identity;
        private final String credential;

        private Account(UUID uuid, String username, String credential) {
            this.identity = new PlayerIdentity(uuid, username);
            this.credential = credential;
        }

        public PlayerIdentity getIdentity() {
            return identity;
        }

        public @NotNull String getCredentialVersion() {
            return credential;
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
        String username = data.usernames.get(playerUuid);
        if (username == null || !normalizedName.equals(normalizeUsername(username))) {
            return null;
        }
        String credential = data.passwords.get(playerUuid);
        return credential == null ? null : new Account(playerUuid, username, credential);
    }

    public static boolean verifyPassword(Account account, String password) {
        return account != null && verifyAccountPassword(account, password);
    }

    private static boolean verifyAccountPassword(Account account, String password) {
        String stored = account.credential;
        if (!stored.equals(instance.passwords.get(account.identity.uuid))) return false;
        try {
            return PasswordHelper.validatePassword(password, stored)
                && stored.equals(instance.passwords.get(account.identity.uuid));
        } catch (Exception e) {
            LOG.error("Password verification failed for player UUID: {}", account.identity.uuid, e);
            return false;
        }
    }

    /** Internal credential version used to revoke sessions without repeating password hashing. */
    public static @Nullable String getCredentialVersion(@NotNull UUID playerUuid) {
        return instance.passwords.get(playerUuid);
    }

    /**
     * Records the canonical name supplied by an online player identity when the owner renames. The UUID remains the
     * account identity; the name is only a case-insensitive login index.
     */
    @SuppressWarnings("ConstantValue") // Keep validation of externally supplied identity data.
    public static synchronized void observePlayer(PlayerIdentity player) {
        if (player == null || player.uuid == null || player.name == null || player.name.isEmpty()) {
            return;
        }
        CoreData data = instance;
        if (!data.passwords.containsKey(player.uuid)) {
            return;
        }
        if (data.recordUsername(player.uuid, player.name)) {
            saveChanges();
        }
    }

    public static synchronized boolean setPassword(PlayerIdentity player, String passwordHash) {
        UUID playerUuid = player.uuid;
        if (passwordHash == null || passwordHash.isEmpty()) {
            instance.passwords.remove(playerUuid);
            AuthService.invalidatePlayer(playerUuid);
            saveChanges();
            return true;
        }

        instance.passwords.put(playerUuid, passwordHash);
        AuthService.invalidatePlayer(playerUuid);
        instance.recordUsername(playerUuid, player.name);
        saveChanges();
        return true;
    }

    private static synchronized void saveChanges() {
        CoreData data = instance;
        try {
            GSONUtils.writeAtomically(dataFile(), data);
        } catch (Exception e) {
            LOG.error("Failed to save web data", e);
        }
    }

    public static synchronized void loadData() {
        Gson gson = GSONUtils.GSON_BUILDER.create();
        File file = dataFile();
        if (!file.exists()) {
            LOG.info("Web data file not found, creating a new one.");
            saveChanges();
            return;
        }
        try {
            CoreData loaded;
            try (Reader reader = Files.newReader(file, StandardCharsets.UTF_8)) {
                loaded = gson.fromJson(reader, CoreData.class);
            }
            if (loaded == null) {
                // An empty or null-valued file deserializes to null without throwing, so this is not
                // reachable from the catch below.
                LOG.error("Web data file is empty or malformed, keeping the accounts already in memory");
                return;
            }
            loaded.validateSkippedUpdate();
            loaded.rebuildUsernameIndex();
            instance = loaded;
        } catch (Exception e) {
            // Deliberately no clear-and-save here: a failed read must not persist the loss of every
            // account. Leave the file alone so it can be inspected or restored.
            LOG.error("Failed to load web data from file: {}", file.getAbsolutePath(), e);
        }
    }

    @SuppressWarnings("ConstantValue") // Gson populates fields from untrusted persisted JSON without constructors.
    private void validateSkippedUpdate() {
        SkippedUpdate skipped = skippedUpdate;
        if (skipped == null) return;
        if (skipped.minecraftVersion() == null || skipped.minecraftVersion()
            .isEmpty()
            || skipped.loader() == null
            || skipped.loader()
                .isEmpty()
            || skipped.tag() == null
            || skipped.tag()
                .isEmpty()
            || !ReleaseManifest.isValidVersion(skipped.version(), skipped.channel())) {
            throw new IllegalArgumentException("Invalid skipped update preference");
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
