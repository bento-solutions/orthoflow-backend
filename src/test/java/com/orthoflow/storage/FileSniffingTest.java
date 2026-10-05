package com.orthoflow.storage;

import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.storage.application.service.FileService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The stored type comes from the bytes, never from what the client claims. */
class FileSniffingTest {

    private static String sniff(byte[] bytes) throws Exception {
        Method m = FileService.class.getDeclaredMethod("sniff", byte[].class);
        m.setAccessible(true);
        try {
            return (String) m.invoke(null, (Object) bytes);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    void recognisesTheAllowedTypesByTheirMagicBytes() throws Exception {
        assertThat(sniff(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})).isEqualTo("image/png");
        assertThat(sniff(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0})).isEqualTo("image/jpeg");
        assertThat(sniff("%PDF-1.7 ...".getBytes(StandardCharsets.US_ASCII))).isEqualTo("application/pdf");
        assertThat(sniff("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1))).isEqualTo("image/webp");
    }

    @Test
    void refusesHtmlAndScriptsWhateverTheyAreNamed() {
        assertThatThrownBy(() -> sniff("<html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> sniff("<svg onload=alert(1)>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> sniff(new byte[0])).isInstanceOf(ValidationException.class);
    }
}
