package pl.kuba6000.ae2webintegration.core;

import java.io.File;
import java.util.Random;

public class Config {

    public static String AE_PASSWORD = new Random().ints(48, 122 + 1)
        .filter(i -> (i <= 57 || i >= 65) && (i <= 90 || i >= 97))
        .limit(16)
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
        .toString();
    public static int AE_PORT = 2324;
    public static boolean ALLOW_NO_PASSWORD_ON_LOCALHOST = true;
    public static boolean AE_PUBLIC_MODE = true;
    public static int AE_MAX_REQUESTS_BEFORE_LOGGED_IN_PER_MINUTE = 20;

    // discord
    public static String DISCORD_WEBHOOK = "";
    public static String DISCORD_ROLE_ID = "";

    // tracking
    // TODO: Add more customization options (order time, size, item type ? etc.)
    public static boolean TRACKING_TRACK_MACHINE_CRAFTING = false;

    // updates
    public static boolean CHECK_FOR_UPDATES = true;

    public static File getConfigFile(String fileName) {
        return new File(WebEngine.getPlatform().getConfigDirectory(), fileName);
    }
}
