package pl.kuba6000.ae2webintegration.core.identity;

import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

/** Immutable resource identity; neither a grid membership assertion nor an authorization grant. */
public final class StableItemKey {

    public static final int MAX_IDENTITY_BYTES = 256 * 1024;
    public static final int MAX_TOKEN_LENGTH = 22;

    private static final HashFunction HASH = Hashing.murmur3_128(0);

    private final long first;
    private final long second;
    private final int hash;

    private StableItemKey(byte[] digest) {
        ByteBuffer bytes = ByteBuffer.wrap(digest);
        first = bytes.getLong();
        second = bytes.getLong();
        hash = 31 * Long.hashCode(first) + Long.hashCode(second);
    }

    /** Native adapters supply deterministic, bounded bytes without amount or crafting state. */
    public static StableItemKey fromIdentityBytes(byte[] identity) throws IOException {
        Objects.requireNonNull(identity, "identity");
        if (identity.length > MAX_IDENTITY_BYTES) throw new IdentityLimitException();
        return new StableItemKey(
            HASH.hashBytes(identity)
                .asBytes());
    }

    public static StableItemKey parse(String token) {
        if (token == null || token.length() != MAX_TOKEN_LENGTH) {
            throw new IllegalArgumentException("Invalid resource key");
        }
        byte[] digest = Base64.getUrlDecoder()
            .decode(token);
        if (digest.length != 16 || !Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(digest)
            .equals(token)) {
            throw new IllegalArgumentException("Noncanonical resource key");
        }
        return new StableItemKey(digest);
    }

    /** Length-prefixed strict UTF-8 shared by native encoders; not Java's modified UTF format. */
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

    @Override
    public String toString() {
        byte[] digest = ByteBuffer.allocate(16)
            .putLong(first)
            .putLong(second)
            .array();
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(digest);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof StableItemKey)) return false;
        StableItemKey key = (StableItemKey) other;
        return first == key.first && second == key.second;
    }

    @Override
    public int hashCode() {
        return hash;
    }
}
