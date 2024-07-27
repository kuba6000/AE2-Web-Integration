package com.kuba6000.ae2webintegration.utils;

import java.util.SplittableRandom;

import net.minecraft.util.EnumChatFormatting;

public class StringUtils {

    private static final SplittableRandom random = new SplittableRandom();
    private static final String[] rainbow = new String[] { EnumChatFormatting.DARK_RED.toString(),
        EnumChatFormatting.RED.toString(), EnumChatFormatting.GOLD.toString(), EnumChatFormatting.YELLOW.toString(),
        EnumChatFormatting.DARK_GREEN.toString(), EnumChatFormatting.GREEN.toString(),
        EnumChatFormatting.AQUA.toString(), EnumChatFormatting.DARK_AQUA.toString(),
        EnumChatFormatting.DARK_BLUE.toString(), EnumChatFormatting.BLUE.toString(),
        EnumChatFormatting.LIGHT_PURPLE.toString(), EnumChatFormatting.DARK_PURPLE.toString(),
        EnumChatFormatting.WHITE.toString(), EnumChatFormatting.GRAY.toString(),
        EnumChatFormatting.DARK_GRAY.toString(), };

    public static String applyRainbow(String str, int offset, String additional) {
        StringBuilder final_string = new StringBuilder();
        int i = offset;
        for (char c : str.toCharArray()) final_string.append(rainbow[i++ % rainbow.length])
            .append(additional)
            .append(c);
        return final_string.toString();
    }

    public static String applyRandomRainbow(String str, String additional) {
        return applyRainbow(str, random.nextInt(rainbow.length), additional);
    }

}
