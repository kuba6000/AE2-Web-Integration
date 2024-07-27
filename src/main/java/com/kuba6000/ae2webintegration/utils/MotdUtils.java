package com.kuba6000.ae2webintegration.utils;

import java.util.Collections;

public class MotdUtils {

    public static int MOTD_MAX_CHARACTERS_IN_LINE = 62;

    public static String centerTheLine(String toCenter) {
        String toCenterStripped = toCenter;
        int i;
        while ((i = toCenterStripped.indexOf(167)) != -1) {
            toCenterStripped = toCenterStripped.substring(0, i) + toCenterStripped.substring(i + 2);
        }

        if (toCenterStripped.length() >= MOTD_MAX_CHARACTERS_IN_LINE) return toCenter;
        int delta = MOTD_MAX_CHARACTERS_IN_LINE - toCenterStripped.length();
        int first = delta / 2;
        int second = delta - first;

        return String.join("", Collections.nCopies(first, " ")) + toCenter
            + String.join("", Collections.nCopies(second, " "));
    }

    // _______! Hello, stranger! What are you looking for? !_______
}
