package org.booklore.service.koreader;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KoreaderPasswordsTest {

    private static final String MD5_OF_PASSWORD = "5f4dcc3b5aa765d61d8327deb882cf99";

    @Test
    void matchesRegardlessOfHexCase_likeTheOldComparison() {
        assertThat(KoreaderPasswords.md5Matches(MD5_OF_PASSWORD, MD5_OF_PASSWORD)).isTrue();
        assertThat(KoreaderPasswords.md5Matches(MD5_OF_PASSWORD, MD5_OF_PASSWORD.toUpperCase())).isTrue();
    }

    @Test
    void rejectsWrongMissingOrMalformedKeys() {
        assertThat(KoreaderPasswords.md5Matches(MD5_OF_PASSWORD, "5f4dcc3b5aa765d61d8327deb882cf98")).isFalse();
        assertThat(KoreaderPasswords.md5Matches(MD5_OF_PASSWORD, null)).isFalse();
        assertThat(KoreaderPasswords.md5Matches(null, MD5_OF_PASSWORD)).isFalse();
        assertThat(KoreaderPasswords.md5Matches(MD5_OF_PASSWORD, "not-hex")).isFalse();
        assertThat(KoreaderPasswords.md5Matches(MD5_OF_PASSWORD, "")).isFalse();
    }
}
