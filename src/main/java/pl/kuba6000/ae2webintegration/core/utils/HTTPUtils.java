package pl.kuba6000.ae2webintegration.core.utils;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

public class HTTPUtils {

    /**
     * Parses a query parameter that must be a number.
     * <p>
     * Returns {@code null} rather than a fallback on purpose: {@code -1} is the sentinel for "no grid
     * requested", so quietly turning garbage into a fallback would read as a valid request instead of
     * being rejected. Callers deny; nothing here throws, because request parsing runs outside any
     * try/catch and an exception would drop the connection with no response at all.
     */
    public static Long parseLong(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** @see #parseLong(String) */
    public static Integer parseInt(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("CharsetObjectCanBeUsed") // URLDecoder's Charset overload is unavailable on Java 8.
    public static Map<String, String> parseQueryString(String qs) {
        Map<String, String> result = new HashMap<>();
        if (qs == null) return result;

        int last = 0, next, l = qs.length();
        while (last < l) {
            next = qs.indexOf('&', last);
            if (next == -1) next = l;

            if (next > last) {
                int eqPos = qs.indexOf('=', last);
                try {
                    if (eqPos < 0 || eqPos > next) result.put(URLDecoder.decode(qs.substring(last, next), "utf-8"), "");
                    else result.put(
                        URLDecoder.decode(qs.substring(last, eqPos), "utf-8"),
                        URLDecoder.decode(qs.substring(eqPos + 1, next), "utf-8"));
                } catch (UnsupportedEncodingException e) {
                    throw new RuntimeException(e); // will never happen, utf-8 support is mandatory for java
                } catch (IllegalArgumentException e) {
                    // A malformed percent escape such as %ZZ. This is an unauthenticated boundary, so one
                    // broken parameter must not decide the fate of the whole request - skip it and go on.
                }
            }
            last = next + 1;
        }
        return result;
    }

}
