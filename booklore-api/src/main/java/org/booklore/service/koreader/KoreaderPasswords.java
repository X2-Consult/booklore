package org.booklore.service.koreader;

import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * KOReader authenticates with the MD5 of the password, sent as hex. Comparing it with
 * String.equalsIgnoreCase returns as soon as a character differs, which leaks how much of a guess
 * was right; compare the digests in constant time instead (Grimmory 564ff93a).
 */
public final class KoreaderPasswords {

    private KoreaderPasswords() {
    }

    public static boolean md5Matches(String storedMd5Hex, String providedMd5Hex) {
        if (storedMd5Hex == null || providedMd5Hex == null) {
            return false;
        }
        try {
            // HexFormat parses either case, so "AB12" and "ab12" still match as before.
            return MessageDigest.isEqual(HexFormat.of().parseHex(storedMd5Hex), HexFormat.of().parseHex(providedMd5Hex));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
