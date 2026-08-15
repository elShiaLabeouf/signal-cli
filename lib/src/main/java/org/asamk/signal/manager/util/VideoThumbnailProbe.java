package org.asamk.signal.manager.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Extracts a real thumbnail preview (as a {@link BlurHash}) from a video file by shelling out to {@code ffmpeg},
 * when it's present on {@code PATH}. This is strictly a nice-to-have on top of the width/height set by
 * {@link MediaDimensionProbe}: {@code ffmpeg} is never a hard runtime requirement, and any failure - missing
 * binary, corrupt input, timeout - silently degrades to no blurhash rather than blocking the send.
 * <p>
 * Only usable against a random-access file (see {@link #extractBlurHash(FileChannel)}): the video is streamed
 * into ffmpeg's stdin via {@link FileChannel#transferTo}, which needs to read from position 0 independently of
 * whatever else is using the channel, without ever buffering the whole file in this process.
 */
final class VideoThumbnailProbe {

    private static final Logger logger = LoggerFactory.getLogger(VideoThumbnailProbe.class);

    // Blurhash is a heavily-downsampled representation, so ffmpeg only needs to hand back a small frame.
    private static final int THUMBNAIL_MAX_DIMENSION = 32;
    private static final int BLURHASH_COMPONENTS_X = 4;
    private static final int BLURHASH_COMPONENTS_Y = 3;
    private static final long FFMPEG_TIMEOUT_MS = 10_000;

    private static volatile Boolean ffmpegAvailable;

    private VideoThumbnailProbe() {
    }

    static Optional<String> extractBlurHash(FileChannel channel) {
        if (!isFfmpegAvailable()) {
            return Optional.empty();
        }
        try {
            final var frame = runFfmpeg(channel);
            if (frame == null) {
                return Optional.empty();
            }
            return Optional.of(BlurHash.encode(frame.rgb(),
                    frame.width(),
                    frame.height(),
                    BLURHASH_COMPONENTS_X,
                    BLURHASH_COMPONENTS_Y));
        } catch (IOException | RuntimeException e) {
            logger.debug("Failed to extract video thumbnail, sending without blurhash: {}", e.getMessage());
            return Optional.empty();
        }
    }

    static boolean isFfmpegAvailable() {
        final var cached = ffmpegAvailable;
        if (cached != null) {
            return cached;
        }
        boolean available;
        try {
            final var process = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            available = process.waitFor(FFMPEG_TIMEOUT_MS, TimeUnit.MILLISECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            available = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            available = false;
        }
        ffmpegAvailable = available;
        return available;
    }

    private static Frame runFfmpeg(FileChannel channel) throws IOException {
        final var process = new ProcessBuilder("ffmpeg",
                "-v",
                "error",
                "-nostdin",
                "-i",
                "pipe:0",
                "-frames:v",
                "1",
                "-vf",
                "scale=" + THUMBNAIL_MAX_DIMENSION + ":-1",
                "-f",
                "image2pipe",
                "-vcodec",
                "ppm",
                "-").start();

        final var stdinWriter = new Thread(() -> {
            try (var out = process.getOutputStream()) {
                channel.transferTo(0, channel.size(), Channels.newChannel(out));
            } catch (IOException e) {
                // ffmpeg closes stdin once it has decoded the single frame it needs - a broken pipe here is
                // expected, not a failure.
            }
        }, "ffmpeg-stdin-writer");
        stdinWriter.setDaemon(true);

        final var stdoutBytes = new byte[1][];
        final var stdoutReader = new Thread(() -> {
            try (var in = process.getInputStream()) {
                stdoutBytes[0] = in.readAllBytes();
            } catch (IOException e) {
                stdoutBytes[0] = new byte[0];
            }
        }, "ffmpeg-stdout-reader");
        stdoutReader.setDaemon(true);

        final var stderrDrain = new Thread(() -> {
            try (var err = process.getErrorStream()) {
                err.readAllBytes();
            } catch (IOException ignored) {
            }
        }, "ffmpeg-stderr-drain");
        stderrDrain.setDaemon(true);

        stdinWriter.start();
        stdoutReader.start();
        stderrDrain.start();

        final boolean finished;
        try {
            finished = process.waitFor(FFMPEG_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return null;
        }
        if (!finished) {
            process.destroyForcibly();
            return null;
        }
        joinQuietly(stdinWriter);
        joinQuietly(stdoutReader);
        joinQuietly(stderrDrain);

        if (process.exitValue() != 0) {
            return null;
        }
        final var ppmBytes = stdoutBytes[0];
        if (ppmBytes == null || ppmBytes.length == 0) {
            return null;
        }
        return parsePpm(ppmBytes);
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Parses a binary PPM (P6) image: header "P6 &lt;width&gt; &lt;height&gt; &lt;maxval&gt;", then raw RGB bytes. */
    private static Frame parsePpm(byte[] data) {
        if (data.length < 2 || data[0] != 'P' || data[1] != '6') {
            return null;
        }
        var pos = 2;
        final var values = new int[3];
        for (var v = 0; v < 3; v++) {
            pos = skipWhitespaceAndComments(data, pos);
            final var start = pos;
            while (pos < data.length && data[pos] >= '0' && data[pos] <= '9') {
                pos++;
            }
            if (pos == start) {
                return null;
            }
            values[v] = Integer.parseInt(new String(data, start, pos - start, StandardCharsets.US_ASCII));
        }
        // Exactly one whitespace byte separates the header from the binary pixel data.
        if (pos >= data.length || !isWhitespace(data[pos])) {
            return null;
        }
        pos++;

        final var width = values[0];
        final var height = values[1];
        final var maxVal = values[2];
        if (width <= 0 || height <= 0 || maxVal <= 0 || maxVal > 255) {
            return null;
        }
        final var expected = width * height * 3;
        if (data.length - pos < expected) {
            return null;
        }
        final var rgb = new byte[expected];
        System.arraycopy(data, pos, rgb, 0, expected);
        return new Frame(rgb, width, height);
    }

    private static int skipWhitespaceAndComments(byte[] data, int pos) {
        while (pos < data.length) {
            if (isWhitespace(data[pos])) {
                pos++;
            } else if (data[pos] == '#') {
                while (pos < data.length && data[pos] != '\n') {
                    pos++;
                }
            } else {
                break;
            }
        }
        return pos;
    }

    private static boolean isWhitespace(byte b) {
        return b == ' ' || b == '\t' || b == '\n' || b == '\r';
    }

    private record Frame(byte[] rgb, int width, int height) {
    }
}
