package pl.kuba6000.ae2webintegration.core.utils;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

public class HTTPUtils {

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
                }
            }
            last = next + 1;
        }
        return result;
    }

}
