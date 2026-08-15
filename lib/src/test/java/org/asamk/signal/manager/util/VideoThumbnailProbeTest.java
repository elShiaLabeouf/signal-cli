package org.asamk.signal.manager.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.io.FileInputStream;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the real {@code ffmpeg} shell-out path. Skips itself (rather than failing) when {@code ffmpeg} isn't
 * on {@code PATH} - this feature is explicitly allowed to be unavailable, so CI/dev environments without
 * ffmpeg installed must still pass.
 */
class VideoThumbnailProbeTest {

    @Test
    public void extractBlurHash_withRealFfmpeg_producesAValidHash() throws Exception {
        Assumptions.assumeTrue(VideoThumbnailProbe.isFfmpegAvailable(), "ffmpeg not found on PATH, skipping");

        final var tempFile = Files.createTempFile("video-thumbnail-probe-test", ".mp4");
        try {
            final var generate = new ProcessBuilder("ffmpeg",
                    "-y",
                    "-v",
                    "error",
                    "-f",
                    "lavfi",
                    "-i",
                    "testsrc=size=64x64:rate=1:duration=1",
                    "-pix_fmt",
                    "yuv420p",
                    tempFile.toString()).start();
            generate.waitFor();
            assertTrue(generate.exitValue() == 0, "failed to generate test fixture with ffmpeg");

            try (var fis = new FileInputStream(tempFile.toFile())) {
                final var hash = VideoThumbnailProbe.extractBlurHash(fis.getChannel());

                assertTrue(hash.isPresent(), "expected a blurhash to be extracted");
                // 1 (size) + 1 (max AC) + 4 (DC) + (4*3-1)*2 (AC) = 28 characters for the 4x3 grid we use.
                assertTrue(hash.get().length() == 28, "unexpected blurhash length: " + hash.get());
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
