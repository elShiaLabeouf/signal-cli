package org.asamk.signal.manager.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlurHashTest {

    @Test
    public void encode_solidColor_producesExpectedLengthAndDecodesToRoughlyTheSameColor() {
        final var width = 8;
        final var height = 8;
        final var rgb = new byte[width * height * 3];
        for (var i = 0; i < rgb.length; i += 3) {
            rgb[i] = (byte) 200; // R
            rgb[i + 1] = (byte) 80; // G
            rgb[i + 2] = (byte) 40; // B
        }

        final var hash = BlurHash.encode(rgb, width, height, 4, 3);

        // 1 (size flag) + 1 (max AC) + 4 (DC) + (4*3 - 1) * 2 (AC components) = 28 characters.
        assertEquals(28, hash.length());
        for (final var c : hash.toCharArray()) {
            assertTrue("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~".indexOf(c)
                    >= 0, "unexpected character in blurhash: " + c);
        }

        // A solid-color image has no AC energy, so decoding the average color back out (the first 4 body
        // characters after the 2 header digits) should reconstruct almost exactly the original color.
        final var decoded = decodeAverageColor(hash);
        assertTrue(Math.abs(decoded[0] - 200) <= 1, "R channel drifted: " + decoded[0]);
        assertTrue(Math.abs(decoded[1] - 80) <= 1, "G channel drifted: " + decoded[1]);
        assertTrue(Math.abs(decoded[2] - 40) <= 1, "B channel drifted: " + decoded[2]);
    }

    @Test
    public void encode_gradient_producesDifferentHashThanSolidColor() {
        final var width = 16;
        final var height = 16;
        final var solid = new byte[width * height * 3];
        final var gradient = new byte[width * height * 3];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                final var offset = (y * width + x) * 3;
                solid[offset] = solid[offset + 1] = solid[offset + 2] = (byte) 128;
                gradient[offset] = (byte) (x * 255 / (width - 1));
                gradient[offset + 1] = (byte) 128;
                gradient[offset + 2] = (byte) (y * 255 / (height - 1));
            }
        }

        final var solidHash = BlurHash.encode(solid, width, height, 4, 3);
        final var gradientHash = BlurHash.encode(gradient, width, height, 4, 3);

        assertTrue(!solidHash.equals(gradientHash));
        // Same DC (average) region length, but the gradient must carry real AC energy (i.e. not all zero).
        final var solidAc = solidHash.substring(6);
        final var gradientAc = gradientHash.substring(6);
        assertTrue(!solidAc.equals(gradientAc));
    }

    @Test
    public void encode_rejectsInvalidComponentCounts() {
        final var rgb = new byte[3 * 3 * 3];
        assertThrows(IllegalArgumentException.class, () -> BlurHash.encode(rgb, 3, 3, 0, 3));
        assertThrows(IllegalArgumentException.class, () -> BlurHash.encode(rgb, 3, 3, 10, 3));
    }

    /**
     * Decodes just the DC (average color) component of a blurhash produced with the header layout this class
     * always uses (1 size digit + 1 max-AC digit + 4 DC digits), enough to verify round-trip fidelity without
     * needing a full decoder.
     */
    private static int[] decodeAverageColor(String hash) {
        final var charset = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~";
        var value = 0;
        for (var i = 0; i < 4; i++) {
            value = value * 83 + charset.indexOf(hash.charAt(2 + i));
        }
        return new int[]{(value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF};
    }
}
