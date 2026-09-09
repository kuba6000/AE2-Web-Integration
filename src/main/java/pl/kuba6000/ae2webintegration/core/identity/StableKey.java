package pl.kuba6000.ae2webintegration.core.identity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Consumer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.hash.PrimitiveSink;

/** Immutable hashed identity; neither a grid membership assertion nor an authorization grant. */
@SuppressWarnings("UnstableApiUsage")
public final class StableKey {

    public static final int MAX_TOKEN_LENGTH = 22;

    private static final int HASH_BYTES = 16;
    private static final HashFunction HASH = Hashing.murmur3_128(0);

    private final long first;
    private final long second;
    private final int hash;
    private volatile @Nullable String text;

    private StableKey(byte @NotNull [] digest) {
        ByteBuffer bytes = ByteBuffer.wrap(digest);
        first = bytes.getLong();
        second = bytes.getLong();
        hash = 31 * Long.hashCode(first) + Long.hashCode(second); // NOPMD - Conventional hash-combining multiplier.
    }

    /** Hash deterministic native identity data directly, without retaining its serialized body. */
    public static @NotNull StableKey create(@NotNull Consumer<PrimitiveSink> writeIdentity) {
        Hasher hasher = HASH.newHasher();
        writeIdentity.accept(hasher);
        return new StableKey(
            hasher.hash()
                .asBytes());
    }

    @SuppressWarnings("ConstantValue") // Validate external tokens even when callers violate the annotation contract.
    public static @NotNull StableKey parse(@NotNull String token) {
        if (token == null || token.length() != MAX_TOKEN_LENGTH) {
            throw new IllegalArgumentException("Invalid resource key");
        }
        byte[] digest = Base64.getUrlDecoder()
            .decode(token);
        if (digest.length != HASH_BYTES || !Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(digest)
            .equals(token)) {
            throw new IllegalArgumentException("Noncanonical resource key");
        }
        return new StableKey(digest);
    }

    /** Big-endian length-prefixed strict UTF-8, independent of native modified-UTF serializers. */
    public static void writeText(@NotNull PrimitiveSink output, @NotNull String value) {
        int length = utf8Length(value);
        output.putInt(Integer.reverseBytes(length));
        output.putString(value, StandardCharsets.UTF_8);
    }

    // UTF-8 uses 1/2/3 bytes below U+0080/U+0800/U+10000; a surrogate pair needs 4 bytes.
    @SuppressWarnings("PMD.AvoidMagicNumbers")
    private static int utf8Length(@NotNull String value) {
        long length = 0;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (Character.isHighSurrogate(character)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IllegalArgumentException("Malformed Unicode in canonical identity");
                }
                length += 4;
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("Malformed Unicode in canonical identity");
            } else {
                length += character < 0x80 ? 1 : character < 0x800 ? 2 : 3;
            }
        }
        if (length > Integer.MAX_VALUE) throw new IllegalArgumentException("Identity text is too long");
        return (int) length;
    }

    @Override
    public @NotNull String toString() {
        String result = text;
        if (result == null) {
            byte[] digest = ByteBuffer.allocate(HASH_BYTES)
                .putLong(first)
                .putLong(second)
                .array();
            result = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(digest);
            text = result;
        }
        return result;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) return true;
        if (!(other instanceof StableKey key)) return false;
        return first == key.first && second == key.second;
    }

    @Override
    public int hashCode() {
        return hash;
    }
}
