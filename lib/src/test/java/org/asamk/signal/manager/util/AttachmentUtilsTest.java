package org.asamk.signal.manager.util;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.whispersystems.signalservice.api.util.StreamDetails;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Optional;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttachmentUtilsTest {

    @Test
    public void createAttachmentStream_setsWidthAndHeightForImage() throws Exception {
        final var imageBytes = pngBytes(37, 21);
        final var streamDetails = new StreamDetails(new ByteArrayInputStream(imageBytes),
                "image/png",
                imageBytes.length);

        final var attachment = AttachmentUtils.createAttachmentStream(streamDetails, Optional.of("meme.png"), null);

        assertEquals(37, attachment.getWidth());
        assertEquals(21, attachment.getHeight());
        assertArrayEquals(imageBytes, attachment.getInputStream().readAllBytes());
    }

    @Test
    public void createAttachmentStream_leavesWidthAndHeightZeroForNonImage() throws Exception {
        final var bytes = "not an image".getBytes();
        final var streamDetails = new StreamDetails(new ByteArrayInputStream(bytes),
                "application/octet-stream",
                bytes.length);

        final var attachment = AttachmentUtils.createAttachmentStream(streamDetails, Optional.of("file.bin"), null);

        assertEquals(0, attachment.getWidth());
        assertEquals(0, attachment.getHeight());
        assertArrayEquals(bytes, attachment.getInputStream().readAllBytes());
    }

    @Test
    public void createAttachmentStream_leavesWidthAndHeightZeroForVideoByDefault() throws Exception {
        // setVideoPreview defaults to off (via the 3-arg overload), so a perfectly valid, well-formed video
        // still gets today's 0/0 behavior unless a caller explicitly opts in.
        final var videoBytes = mp4Bytes(1920, 1080, false);
        final var streamDetails = new StreamDetails(new ByteArrayInputStream(videoBytes),
                "video/mp4",
                videoBytes.length);

        final var attachment = AttachmentUtils.createAttachmentStream(streamDetails,
                Optional.of("clip.mp4"),
                null);

        assertEquals(0, attachment.getWidth());
        assertEquals(0, attachment.getHeight());
        assertArrayEquals(videoBytes, attachment.getInputStream().readAllBytes());
    }

    @Test
    public void createAttachmentStream_setsWidthAndHeightForLandscapeMp4WhenVideoPreviewEnabled() throws Exception {
        final var videoBytes = mp4Bytes(1920, 1080, false);
        final var streamDetails = new StreamDetails(new ByteArrayInputStream(videoBytes),
                "video/mp4",
                videoBytes.length);

        final var attachment = AttachmentUtils.createAttachmentStream(streamDetails,
                Optional.of("clip.mp4"),
                false,
                true,
                null);

        assertEquals(1920, attachment.getWidth());
        assertEquals(1080, attachment.getHeight());
        assertArrayEquals(videoBytes, attachment.getInputStream().readAllBytes());
    }

    @Test
    public void createAttachmentStream_swapsWidthAndHeightForRotatedPortraitMp4WhenVideoPreviewEnabled() throws Exception {
        // Stored landscape (1920x1080) with a 90 degree rotation matrix, as phone cameras record portrait video.
        final var videoBytes = mp4Bytes(1920, 1080, true);
        final var streamDetails = new StreamDetails(new ByteArrayInputStream(videoBytes),
                "video/mp4",
                videoBytes.length);

        final var attachment = AttachmentUtils.createAttachmentStream(streamDetails,
                Optional.of("portrait.mp4"),
                false,
                true,
                null);

        assertEquals(1080, attachment.getWidth());
        assertEquals(1920, attachment.getHeight());
        assertArrayEquals(videoBytes, attachment.getInputStream().readAllBytes());
    }

    @Test
    public void createAttachmentStream_setsWidthAndHeightForMp4FromFileWhenVideoPreviewEnabled() throws Exception {
        final var videoBytes = mp4Bytes(1280, 720, false);
        final var tempFile = Files.createTempFile("attachment-utils-test", ".mp4");
        try {
            Files.write(tempFile, videoBytes);
            try (var fis = new FileInputStream(tempFile.toFile())) {
                final var streamDetails = new StreamDetails(fis, "video/mp4", videoBytes.length);

                final var attachment = AttachmentUtils.createAttachmentStream(streamDetails,
                        Optional.of("clip.mp4"),
                        false,
                        true,
                        null);

                assertEquals(1280, attachment.getWidth());
                assertEquals(720, attachment.getHeight());
                assertArrayEquals(videoBytes, attachment.getInputStream().readAllBytes());
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void createAttachmentStream_leavesWidthAndHeightZeroForAudioOnlyMp4WhenVideoPreviewEnabled() throws Exception {
        final var videoBytes = mp4AudioOnlyBytes();
        final var streamDetails = new StreamDetails(new ByteArrayInputStream(videoBytes),
                "video/mp4",
                videoBytes.length);

        final var attachment = AttachmentUtils.createAttachmentStream(streamDetails,
                Optional.of("voice.mp4"),
                false,
                true,
                null);

        assertEquals(0, attachment.getWidth());
        assertEquals(0, attachment.getHeight());
        assertArrayEquals(videoBytes, attachment.getInputStream().readAllBytes());
    }

    @Test
    public void createAttachmentStream_leavesWidthAndHeightZeroForTruncatedMp4WhenVideoPreviewEnabled() throws Exception {
        final var fullVideoBytes = mp4Bytes(1920, 1080, false);
        final var truncatedBytes = new byte[Math.min(40, fullVideoBytes.length)];
        System.arraycopy(fullVideoBytes, 0, truncatedBytes, 0, truncatedBytes.length);
        final var streamDetails = new StreamDetails(new ByteArrayInputStream(truncatedBytes),
                "video/mp4",
                truncatedBytes.length);

        final var attachment = AttachmentUtils.createAttachmentStream(streamDetails,
                Optional.of("broken.mp4"),
                false,
                true,
                null);

        assertEquals(0, attachment.getWidth());
        assertEquals(0, attachment.getHeight());
        assertArrayEquals(truncatedBytes, attachment.getInputStream().readAllBytes());
    }

    @Test
    public void createAttachmentStream_setsBlurHashForMp4FromFileWhenVideoPreviewEnabledAndFfmpegAvailable() throws Exception {
        Assumptions.assumeTrue(VideoThumbnailProbe.isFfmpegAvailable(), "ffmpeg not found on PATH, skipping");

        final var tempFile = Files.createTempFile("attachment-utils-blurhash-test", ".mp4");
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
            assertEquals(0, generate.exitValue());

            try (var fis = new FileInputStream(tempFile.toFile())) {
                final var streamDetails = new StreamDetails(fis, "video/mp4", Files.size(tempFile));

                final var attachment = AttachmentUtils.createAttachmentStream(streamDetails,
                        Optional.of("clip.mp4"),
                        false,
                        true,
                        null);

                assertTrue(attachment.getWidth() > 0);
                assertTrue(attachment.getHeight() > 0);
                assertTrue(attachment.getBlurHash().isPresent(), "expected a blurhash to be set");
                assertArrayEquals(Files.readAllBytes(tempFile), attachment.getInputStream().readAllBytes());
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void createAttachmentStream_setsWidthAndHeightForWebmWhenVideoPreviewEnabled() throws Exception {
        final var videoBytes = webmBytes(1280, 720);
        final var streamDetails = new StreamDetails(new ByteArrayInputStream(videoBytes),
                "video/webm",
                videoBytes.length);

        final var attachment = AttachmentUtils.createAttachmentStream(streamDetails,
                Optional.of("recording.webm"),
                false,
                true,
                null);

        assertEquals(1280, attachment.getWidth());
        assertEquals(720, attachment.getHeight());
        assertArrayEquals(videoBytes, attachment.getInputStream().readAllBytes());
    }

    private static byte[] pngBytes(final int width, final int height) throws Exception {
        final var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    // --- MP4 / ISO BMFF fixture builders -------------------------------------------------------------------
    //
    // These hand-build the minimal box tree AttachmentUtils' MP4 probe actually reads (ftyp, moov > trak >
    // tkhd + mdia > hdlr), rather than shelling out to ffmpeg, since this environment has no ffmpeg on PATH.
    // To regenerate more realistic fixtures with ffmpeg instead:
    //   ffmpeg -f lavfi -i color=c=blue:s=1920x1080:d=1 -frames:v 1 clip.mp4
    //   ffmpeg -f lavfi -i color=c=blue:s=1080x1920:d=1 -vf "transpose=1" -frames:v 1 -metadata:s:v rotate=90 portrait.mp4

    private static byte[] mp4Bytes(int width, int height, boolean rotate90) {
        final var moov = box("moov", trakBox(width, height, rotate90, true));
        final var ftyp = box("ftyp", "isom".getBytes(StandardCharsets.US_ASCII));
        final var buf = ByteBuffer.allocate(ftyp.length + moov.length);
        buf.put(ftyp).put(moov);
        return buf.array();
    }

    private static byte[] mp4AudioOnlyBytes() {
        final var moov = box("moov", trakBox(0, 0, false, false));
        final var ftyp = box("ftyp", "isom".getBytes(StandardCharsets.US_ASCII));
        return concat(ftyp, moov);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        final var buf = ByteBuffer.allocate(a.length + b.length);
        buf.put(a).put(b);
        return buf.array();
    }

    private static byte[] trakBox(int width, int height, boolean rotate90, boolean video) {
        final var tkhd = box("tkhd", tkhdBody(width, height, rotate90));
        final var hdlr = box("hdlr", hdlrBody(video ? "vide" : "soun"));
        final var mdia = box("mdia", hdlr);
        return box("trak", concat(tkhd, mdia));
    }

    private static byte[] tkhdBody(int width, int height, boolean rotate90) {
        final var buf = ByteBuffer.allocate(84);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.putInt(0); // version(1) + flags(3)
        buf.putInt(0); // creation_time
        buf.putInt(0); // modification_time
        buf.putInt(1); // track_ID
        buf.putInt(0); // reserved
        buf.putInt(0); // duration
        buf.putLong(0); // reserved x2
        buf.putShort((short) 0); // layer
        buf.putShort((short) 0); // alternate_group
        buf.putShort((short) 0); // volume
        buf.putShort((short) 0); // reserved
        if (rotate90) {
            buf.putInt(0).putInt(0x10000).putInt(0);
            buf.putInt(-0x10000).putInt(0).putInt(0);
            buf.putInt(0).putInt(0).putInt(0x40000000);
        } else {
            buf.putInt(0x10000).putInt(0).putInt(0);
            buf.putInt(0).putInt(0x10000).putInt(0);
            buf.putInt(0).putInt(0).putInt(0x40000000);
        }
        buf.putInt(width << 16);
        buf.putInt(height << 16);
        return buf.array();
    }

    private static byte[] hdlrBody(String handlerType) {
        final var buf = ByteBuffer.allocate(12);
        buf.putInt(0); // version(1) + flags(3)
        buf.putInt(0); // pre_defined
        buf.put(handlerType.getBytes(StandardCharsets.US_ASCII));
        return buf.array();
    }

    private static byte[] box(String type, byte[] body) {
        final var buf = ByteBuffer.allocate(8 + body.length);
        buf.putInt(8 + body.length);
        buf.put(type.getBytes(StandardCharsets.US_ASCII));
        buf.put(body);
        return buf.array();
    }

    // --- WebM / EBML fixture builder -------------------------------------------------------------------------
    //
    // To regenerate a realistic fixture with ffmpeg instead:
    //   ffmpeg -f lavfi -i color=c=blue:s=1280x720:d=1 -frames:v 1 recording.webm

    private static byte[] webmBytes(int width, int height) {
        final var pixelWidth = ebmlElement(0xB0, ebmlUint(width));
        final var pixelHeight = ebmlElement(0xBA, ebmlUint(height));
        final var video = ebmlElement(0xE0, concat(pixelWidth, pixelHeight));
        final var trackType = ebmlElement(0x83, ebmlUint(1)); // 1 = video
        final var trackEntry = ebmlElement(0xAE, concat(trackType, video));
        final var tracks = ebmlElement(0x1654AE6BL, trackEntry);
        final var segment = ebmlElement(0x18538067L, tracks);
        final var ebmlHeader = ebmlElement(0x1A45DFA3L, new byte[0]);
        return concat(ebmlHeader, segment);
    }

    private static byte[] ebmlUint(long value) {
        // Smallest big-endian encoding that fits (2 bytes is plenty for realistic pixel dimensions).
        if (value <= 0xFF) {
            return new byte[]{(byte) value};
        }
        return new byte[]{(byte) (value >> 8), (byte) value};
    }

    private static byte[] ebmlElement(long id, byte[] body) {
        final var idBytes = ebmlId(id);
        final var sizeBytes = ebmlSize4(body.length);
        final var buf = ByteBuffer.allocate(idBytes.length + sizeBytes.length + body.length);
        buf.put(idBytes).put(sizeBytes).put(body);
        return buf.array();
    }

    private static byte[] ebmlId(long id) {
        if (id <= 0xFFL) {
            return new byte[]{(byte) id};
        }
        return new byte[]{(byte) (id >> 24), (byte) (id >> 16), (byte) (id >> 8), (byte) id};
    }

    /** Always encodes as a 4-byte EBML VINT (marker 0001xxxx), regardless of value - simplest for small fixtures. */
    private static byte[] ebmlSize4(long value) {
        final var encoded = 0x10000000L | value;
        return new byte[]{(byte) (encoded >> 24), (byte) (encoded >> 16), (byte) (encoded >> 8), (byte) encoded};
    }
}
