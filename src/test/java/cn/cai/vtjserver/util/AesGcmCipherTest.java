package cn.cai.vtjserver.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmCipherTest {

    private final AesGcmCipher cipher = new AesGcmCipher("unit-test-secret");

    @Test
    void roundTripRestoresPlaintext() {
        String plain = "sk-1234567890-secret-key";
        String encrypted = cipher.encrypt(plain);

        assertThat(encrypted).isNotEqualTo(plain);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(plain);
    }

    @Test
    void encryptionIsNonDeterministicPerCall() {
        String plain = "sk-same-input";
        assertThat(cipher.encrypt(plain)).isNotEqualTo(cipher.encrypt(plain));
    }

    @Test
    void blankAndNullPassThroughUnchanged() {
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.encrypt("")).isEmpty();
        assertThat(cipher.decrypt(null)).isNull();
    }

    @Test
    void tamperedCiphertextIsRejected() {
        String encrypted = cipher.encrypt("secret");
        char replacement = encrypted.charAt(0) == 'A' ? 'B' : 'A';
        String tampered = replacement + encrypted.substring(1);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void wrongKeyCannotDecrypt() {
        String encrypted = cipher.encrypt("secret");
        AesGcmCipher other = new AesGcmCipher("a-different-secret");

        assertThatThrownBy(() -> other.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class);
    }
}
