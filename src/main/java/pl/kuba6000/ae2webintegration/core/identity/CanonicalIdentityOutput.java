package pl.kuba6000.ae2webintegration.core.identity;

import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Binary primitives shared by native identity writers; this is not Java's modified-UTF format. */
public final class CanonicalIdentityOutput {

    /** Maximum adapter-written identity body; the fixed domain prefix is not included. */
    public static final int MAX_IDENTITY_BYTES = 256 * 1024;

    private CanonicalIdentityOutput() {}

    public static void writeText(DataOutput output, String value) throws IOException {
        int length = utf8Length(value);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(length);
        output.write(bytes);
    }

    private static int utf8Length(String value) throws IOException {
        if (value.length() > MAX_IDENTITY_BYTES) throw new IdentityLimitException();
        int length = 0;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (Character.isHighSurrogate(character)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IOException("Malformed Unicode in canonical identity");
                }
                length += 4;
            } else if (Character.isLowSurrogate(character)) {
                throw new IOException("Malformed Unicode in canonical identity");
            } else {
                length += character < 0x80 ? 1 : character < 0x800 ? 2 : 3;
            }
            if (length > MAX_IDENTITY_BYTES) throw new IdentityLimitException();
        }
        return length;
    }
}
