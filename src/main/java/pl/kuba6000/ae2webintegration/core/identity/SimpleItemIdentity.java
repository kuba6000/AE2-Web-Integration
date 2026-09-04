package pl.kuba6000.ae2webintegration.core.identity;

import java.util.Objects;

/** Registry-addressable resource identity without platform objects or mutable stack state. */
public final class SimpleItemIdentity {

    public enum Kind {
        ITEM,
        FLUID
    }

    private final Kind kind;
    private final String registryId;
    private final int metadata;

    private SimpleItemIdentity(Kind kind, String registryId, int metadata) {
        validateText(registryId);
        if (kind == Kind.ITEM) {
            int separator = registryId.indexOf(':');
            if (separator <= 0 || separator == registryId.length() - 1 || separator != registryId.lastIndexOf(':')) {
                throw new IllegalArgumentException("Item registry identity must have a namespace and path");
            }
        }
        this.kind = kind;
        this.registryId = registryId;
        this.metadata = metadata;
    }

    public static SimpleItemIdentity item(String registryId, int metadata) {
        return new SimpleItemIdentity(Kind.ITEM, registryId, metadata);
    }

    public static SimpleItemIdentity fluid(String registryId) {
        return new SimpleItemIdentity(Kind.FLUID, registryId, 0);
    }

    public Kind getKind() {
        return kind;
    }

    public String getRegistryId() {
        return registryId;
    }

    public int getMetadata() {
        return metadata;
    }

    static void validateText(String value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing identity text");
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (Character.isISOControl(character)) throw new IllegalArgumentException("Control character in identity");
            if (Character.isHighSurrogate(character)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IllegalArgumentException("Malformed Unicode in identity");
                }
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("Malformed Unicode in identity");
            }
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SimpleItemIdentity)) return false;
        SimpleItemIdentity identity = (SimpleItemIdentity) other;
        return kind == identity.kind && metadata == identity.metadata && registryId.equals(identity.registryId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, registryId, metadata);
    }
}
