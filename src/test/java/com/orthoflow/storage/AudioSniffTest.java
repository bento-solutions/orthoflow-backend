package com.orthoflow.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.storage.application.service.FileService;
import org.junit.jupiter.api.Test;

/** A consultation's recording is typed from its bytes, like every upload, and only audio is kept as audio. */
class AudioSniffTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[Math.max(values.length, 16)];
        for (int i = 0; i < values.length; i++) out[i] = (byte) values[i];
        return out;
    }

    @Test
    void recognisesWhatBrowsersRecord() {
        assertThat(FileService.sniffAudio(bytes(0x1A, 0x45, 0xDF, 0xA3))).isEqualTo("audio/webm");
        assertThat(FileService.sniffAudio(bytes('O', 'g', 'g', 'S'))).isEqualTo("audio/ogg");
        assertThat(FileService.sniffAudio(bytes(0, 0, 0, 0x20, 'f', 't', 'y', 'p'))).isEqualTo("audio/mp4");
        assertThat(FileService.sniffAudio(bytes('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'))).isEqualTo("audio/wav");
    }

    @Test
    void refusesAnythingElse() {
        assertThatThrownBy(() -> FileService.sniffAudio("<html><script>".getBytes()))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> FileService.sniffAudio(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void theGenericUploadStillRefusesAudio() {
        assertThatThrownBy(() -> FileService.sniff(bytes(0x1A, 0x45, 0xDF, 0xA3)))
                .isInstanceOf(ValidationException.class);
    }
}
