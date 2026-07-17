package pl.kuba6000.ae2webintegration.core;

import java.io.File;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.io.Files;
import com.google.gson.Gson;

import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;

public class CoreData {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration");

    static CoreData instance = new CoreData();

    private static final File dataFile = Config.getConfigFile("webdata.json");

    private HashMap<UUID, Integer> UUIDToId = new HashMap<>();
    private HashMap<Integer, UUID> IdToUUID = new HashMap<>();
    private HashMap<UUID, String> passwords = new HashMap<>();

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
        if (instance.passwords.containsKey(id)) {
            try {
                return PasswordHelper.validatePassword(password, instance.passwords.get(id));
            } catch (Exception e) {
                LOG.error("Password verification failed for player ID: " + playerId, e);
                return false;
            }
        }

        return false;
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
        Gson gson = GSONUtils.GSON_BUILDER.create();
        Writer writer = null;
        try {
            writer = Files.newWriter(dataFile, StandardCharsets.UTF_8);
            gson.toJson(instance, writer);
            writer.flush();
            writer.close();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (writer != null) try {
                writer.close();
            } catch (Exception ignored) {}
        }
    }

    public static void loadData() {
        Gson gson = GSONUtils.GSON_BUILDER.create();
        if (!dataFile.exists()) {
            LOG.info("Web data file not found, creating a new one.");
            saveChanges();
            return;
        }
        Reader reader = null;
        try {
            reader = Files.newReader(dataFile, StandardCharsets.UTF_8);
            instance = gson.fromJson(reader, CoreData.class);
        } catch (Exception e) {
            LOG.error("Failed to load web data from file: " + dataFile.getAbsolutePath(), e);
            instance.UUIDToId.clear();
            instance.IdToUUID.clear();
            instance.passwords.clear();
            saveChanges();
        } finally {
            if (reader != null) try {
                reader.close();
            } catch (Exception ignored) {}
        }

    }

}
