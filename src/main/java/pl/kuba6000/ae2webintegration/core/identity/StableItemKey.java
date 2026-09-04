package pl.kuba6000.ae2webintegration.core.identity;

import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** Versioned external resource selector. A key is never an authorization grant. */
public final class StableItemKey {

    public static final int MAX_TOKEN_LENGTH = 512;

    @FunctionalInterface
    public interface IdentityWriter {

        void write(DataOutput output) throws IOException;
    }

    private final String token;
    private final SimpleItemIdentity simpleIdentity;

    private StableItemKey(String token, SimpleItemIdentity simpleIdentity) {
        this.token = token;
        this.simpleIdentity = simpleIdentity;
    }

    public static StableItemKey of(SimpleItemIdentity identity) {
        String token = identity.getKind() == SimpleItemIdentity.Kind.ITEM
            ? "ik1:i:" + identity.getRegistryId() + ":" + identity.getMetadata()
            : "ik1:f:" + identity.getRegistryId();
        validateToken(token);
        return new StableItemKey(token, identity);
    }

    public static StableItemKey parse(String token) {
        validateToken(token);
        if (token.startsWith("ik1:i:")) {
            int delimiter = token.lastIndexOf(':');
            if (delimiter <= 6) throw new IllegalArgumentException("Missing item registry identity");
            StableItemKey key = of(
                SimpleItemIdentity
                    .item(token.substring(6, delimiter), Integer.parseInt(token.substring(delimiter + 1))));
            if (!key.token.equals(token)) throw new IllegalArgumentException("Noncanonical item key");
            return key;
        }
        if (token.startsWith("ik1:f:")) return of(SimpleItemIdentity.fluid(token.substring(6)));
        if (token.startsWith("ik1:h:") && token.length() == 49) {
            String encoded = token.substring(6);
            byte[] digest = Base64.getUrlDecoder()
                .decode(encoded);
            if (digest.length == 32 && Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(digest)
                .equals(encoded)) {
                return new StableItemKey(token, null);
            }
        }
        throw new IllegalArgumentException("Unsupported item key");
    }

    /**
     * Streams a trusted adapter's canonical identity body into the V1 digest. The writer must propagate
     * encoding errors; this boundary bounds output bytes, not native traversal or codec allocation.
     */
    public static StableItemKey complex(IdentityWriter writer) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Java runtime does not support SHA-256", e);
        }
        digest.update("AE2WI_ITEM_KEY\0V1\0".getBytes(StandardCharsets.US_ASCII));
        BoundedDigestOutput output = new BoundedDigestOutput(digest);
        writer.write(new DataOutputStream(output));
        output.checkLimit(0);
        return new StableItemKey(
            "ik1:h:" + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(digest.digest()),
            null);
    }

    private static final class BoundedDigestOutput extends OutputStream {

        private final MessageDigest digest;
        private int written;
        private boolean limitExceeded;

        private BoundedDigestOutput(MessageDigest digest) {
            this.digest = digest;
        }

        private void checkLimit(int length) throws IdentityLimitException {
            if (limitExceeded || length > CanonicalIdentityOutput.MAX_IDENTITY_BYTES - written) {
                limitExceeded = true;
                throw new IdentityLimitException();
            }
        }

        @Override
        public void write(int value) throws IOException {
            checkLimit(1);
            digest.update((byte) value);
            written++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
            checkLimit(length);
            digest.update(bytes, offset, length);
            written += length;
        }
    }

    private static void validateToken(String token) {
        if (token == null || token.length() > MAX_TOKEN_LENGTH)
            throw new IllegalArgumentException("Invalid key length");
        SimpleItemIdentity.validateText(token);
    }

    public SimpleItemIdentity getSimpleIdentity() {
        return simpleIdentity;
    }

    @Override
    public String toString() {
        return token;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StableItemKey && token.equals(((StableItemKey) other).token);
    }

    @Override
    public int hashCode() {
        return token.hashCode();
    }
}
