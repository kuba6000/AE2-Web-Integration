package pl.kuba6000.ae2webintegration.core;

import static pl.kuba6000.ae2webintegration.core.AE2WebIntegration.LOG;

import java.io.File;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

import net.neoforged.neoforge.server.ServerLifecycleHooks;

import com.google.common.io.Files;
import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;

import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;

public class WebData {

    static WebData instance = new WebData();

    private static final File dataFile = Config.getConfigFile("webdata.json");

    private HashMap<UUID, Integer> UUIDToId = new HashMap<>();
    private HashMap<Integer, UUID> IdToUUID = new HashMap<>();
    private HashMap<UUID, String> passwords = new HashMap<>();

    public static int getPlayerId(String name) {
        if (name == null || name.isEmpty()) {
            return -1;
        }
        Optional<GameProfile> profile = ServerLifecycleHooks.getCurrentServer()
            .getProfileCache()
            .get(name);
        if (!profile.isPresent()) {
            return -1;
        }
        Integer id = instance.UUIDToId.get(
            profile.get()
                .getId());
        if (id != null) {
            return id;
        }

        return -1;
    }

    public static boolean verifyPassword(int playerId, String password) {
        UUID id = instance.IdToUUID.get(playerId);
        if (id == null) {
            LOG.warn("Player ID {} not found in IdToUUID map.", playerId);
            return false;
        }
        if (instance.passwords.containsKey(id)) {
            try {
                return PasswordHelper.validatePassword(password, instance.passwords.get(id));
            } catch (Exception e) {
                LOG.error("Password verification failed for player ID: {}", playerId, e);
                return false;
            }
        }

        return false;
    }

    public static void setPassword(GameProfile playerId, String passwordHash) {
        if (passwordHash == null || passwordHash.isEmpty()) {
            instance.passwords.remove(playerId.getId());
        } else {
            try {
                instance.passwords.put(playerId.getId(), passwordHash);
                int pID = AE2Controller.AE2Interface.web$getPlayerData()
                    .web$getPlayerId(playerId);
                instance.UUIDToId.put(playerId.getId(), pID);
                instance.IdToUUID.put(pID, playerId.getId());
            } catch (Exception e) {
                LOG.error("Failed to set password for player ID: {}", playerId, e);
            }
        }
        saveChanges();
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
            instance = gson.fromJson(reader, WebData.class);
        } catch (Exception e) {
            LOG.error("Failed to load web data from file: {}", dataFile.getAbsolutePath(), e);
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
