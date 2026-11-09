package pl.kuba6000.ae2webintegration.core.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.google.common.io.BaseEncoding;
import com.google.gson.JsonObject;

class JarProofTest {

    @Test
    void matchesSaltedDigestWireProtocolVector() {
        BaseEncoding hex = BaseEncoding.base16()
            .lowerCase();
        // Independently calculated SHA-256 of the raw three-byte file "abc".
        byte[] artifact = hex.decode("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        byte[] salt = hex.decode("000102030405060708090a0b0c0d0e0f");
        JsonObject proof = JarProof.create(artifact, salt);
        assertEquals(
            "000102030405060708090a0b0c0d0e0f",
            proof.get("salt")
                .getAsString());
        assertEquals(
            "39d01afa77ef00d058e54688f1dcb4f2f97d575a6e13da8b080b5422b09bdc07",
            proof.get("sha256")
                .getAsString());
        assertEquals(
            2,
            proof.entrySet()
                .size());
    }
}
