package pl.kuba6000.ae2webintegration.core.telemetry;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.jetbrains.annotations.NotNull;

import com.google.common.io.BaseEncoding;
import com.google.gson.JsonObject;

/** Salted artifact proof; the unsalted artifact digest never enters the report. */
final class JarProof {

    private static final BaseEncoding HEX = BaseEncoding.base16()
        .lowerCase();

    private JarProof() {}

    static @NotNull JsonObject create(byte @NotNull [] artifactDigest, byte @NotNull [] salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            digest.update(artifactDigest);
            JsonObject proof = new JsonObject();
            proof.addProperty("salt", HEX.encode(salt));
            proof.addProperty("sha256", HEX.encode(digest.digest()));
            return proof;
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
