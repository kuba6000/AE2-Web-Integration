package pl.kuba6000.ae2webintegration.core;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import com.google.common.base.Ascii;
import com.google.common.io.BaseEncoding;

public class PasswordHelper {

    private static final int ITERATIONS = 65536;
    private static final int HASH_LENGTH_BITS = 512;
    private static final int SALT_BYTES = 16;
    private static final int DEFAULT_PASSWORD_LENGTH = 16;
    private static final BaseEncoding HEX = BaseEncoding.base16()
        .lowerCase();

    public static String generateStrongPasswordHash(String password)
        throws NoSuchAlgorithmException, InvalidKeySpecException {
        char[] chars = password.toCharArray();
        byte[] salt = getSalt();

        PBEKeySpec spec = new PBEKeySpec(chars, salt, ITERATIONS, HASH_LENGTH_BITS);
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");

        byte[] hash = skf.generateSecret(spec)
            .getEncoded();
        return ITERATIONS + ":" + HEX.encode(salt) + ":" + HEX.encode(hash);
    }

    private static byte[] getSalt() throws NoSuchAlgorithmException {
        SecureRandom sr = SecureRandom.getInstance("SHA1PRNG");
        byte[] salt = new byte[SALT_BYTES];
        sr.nextBytes(salt);
        return salt;
    }

    public static boolean validatePassword(String originalPassword, String storedPassword)
        throws NoSuchAlgorithmException, InvalidKeySpecException {
        String[] parts = storedPassword.split(":");
        int iterations = Integer.parseInt(parts[0]);

        byte[] salt = HEX.decode(Ascii.toLowerCase(parts[1]));
        byte[] hash = HEX.decode(Ascii.toLowerCase(parts[2]));

        // Key length is in bits.
        PBEKeySpec spec = new PBEKeySpec(originalPassword.toCharArray(), salt, iterations, hash.length * Byte.SIZE);
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        byte[] testHash = skf.generateSecret(spec)
            .getEncoded();

        int diff = hash.length ^ testHash.length;
        for (int i = 0; i < hash.length && i < testHash.length; i++) {
            diff |= hash[i] ^ testHash[i];
        }
        return diff == 0;
    }

    /**
     * Generates a random 16-character alphanumeric password as the config
     * default. When no password is set in the config file this default is
     * persisted, preventing the "empty password → any password accepted"
     * vulnerability.
     * <p>
     * Uses {@link SecureRandom} for the same reason session tokens do: this value ends up in the config
     * file as the admin password, and java.util.Random derives its 48-bit seed from the clock.
     */
    public static String generateDefaultPassword() {
        return generateToken(DEFAULT_PASSWORD_LENGTH);
    }

    /** Generates a cryptographically random ASCII alphanumeric token of the requested length. */
    public static String generateToken(int length) {
        return new SecureRandom().ints('0', 'z' + 1)
            .filter(i -> (i <= '9' || i >= 'A') && (i <= 'Z' || i >= 'a'))
            .limit(length)
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
            .toString();
    }
}
