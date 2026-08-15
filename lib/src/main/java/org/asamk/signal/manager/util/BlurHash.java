package org.asamk.signal.manager.util;

/**
 * Encodes a small sRGB pixel grid into a <a href="https://blurha.sh">BlurHash</a> string: a compact, lossy
 * representation of an image's rough color/shape that a client can render immediately, before the real
 * attachment has downloaded. Encode-only (signal-cli never needs to decode one), hand-ported from the reference
 * algorithm at https://github.com/woltapp/blurhash/blob/master/Algorithm.md so it matches other implementations
 * bit-for-bit.
 */
final class BlurHash {

    private static final String CHARSET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~";

    private BlurHash() {
    }

    /**
     * @param rgb row-major sRGB pixels, 3 bytes (R, G, B) each, {@code width * height * 3} bytes total
     * @param componentsX number of DCT components along the X axis, 1-9
     * @param componentsY number of DCT components along the Y axis, 1-9
     */
    static String encode(byte[] rgb, int width, int height, int componentsX, int componentsY) {
        if (width <= 0 || height <= 0 || rgb.length < width * height * 3) {
            throw new IllegalArgumentException("invalid pixel buffer");
        }
        if (componentsX < 1 || componentsX > 9 || componentsY < 1 || componentsY > 9) {
            throw new IllegalArgumentException("componentsX/Y must be between 1 and 9");
        }

        final var factors = new float[componentsY][componentsX][];
        for (var j = 0; j < componentsY; j++) {
            for (var i = 0; i < componentsX; i++) {
                final var normalisation = i == 0 && j == 0 ? 1f : 2f;
                factors[j][i] = multiplyBasisFunction(rgb, width, height, i, j, normalisation);
            }
        }

        final var result = new StringBuilder();
        final var sizeFlag = componentsX - 1 + (componentsY - 1) * 9;
        result.append(encode83(sizeFlag, 1));

        final var acCount = componentsX * componentsY - 1;
        float maximumValue;
        if (acCount > 0) {
            var actualMaximumValue = 0f;
            for (var j = 0; j < componentsY; j++) {
                for (var i = 0; i < componentsX; i++) {
                    if (i == 0 && j == 0) {
                        continue;
                    }
                    final var f = factors[j][i];
                    actualMaximumValue = Math.max(actualMaximumValue,
                            Math.max(Math.abs(f[0]), Math.max(Math.abs(f[1]), Math.abs(f[2]))));
                }
            }
            final var quantisedMaximumValue = (int) Math.max(0, Math.min(82, Math.floor(actualMaximumValue * 166 - 0.5)));
            maximumValue = (quantisedMaximumValue + 1) / 166f;
            result.append(encode83(quantisedMaximumValue, 1));
        } else {
            maximumValue = 1f;
            result.append(encode83(0, 1));
        }

        result.append(encode83(encodeDC(factors[0][0]), 4));

        for (var j = 0; j < componentsY; j++) {
            for (var i = 0; i < componentsX; i++) {
                if (i == 0 && j == 0) {
                    continue;
                }
                result.append(encode83(encodeAC(factors[j][i], maximumValue), 2));
            }
        }
        return result.toString();
    }

    private static float[] multiplyBasisFunction(byte[] rgb, int width, int height, int i, int j, float normalisation) {
        var r = 0f;
        var g = 0f;
        var b = 0f;
        for (var y = 0; y < height; y++) {
            final var basisY = (float) Math.cos(Math.PI * j * y / height);
            final var rowOffset = y * width * 3;
            for (var x = 0; x < width; x++) {
                final var basis = normalisation * (float) Math.cos(Math.PI * i * x / width) * basisY;
                final var offset = rowOffset + x * 3;
                r += basis * sRGBToLinear(rgb[offset] & 0xFF);
                g += basis * sRGBToLinear(rgb[offset + 1] & 0xFF);
                b += basis * sRGBToLinear(rgb[offset + 2] & 0xFF);
            }
        }
        final var scale = 1f / (width * height);
        return new float[]{r * scale, g * scale, b * scale};
    }

    private static float sRGBToLinear(int value) {
        final var v = value / 255f;
        return v <= 0.04045f ? v / 12.92f : (float) Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static int linearToSRGB(float value) {
        final var v = Math.max(0, Math.min(1, value));
        if (v <= 0.0031308f) {
            return (int) (v * 12.92f * 255f + 0.5f);
        }
        return (int) ((1.055 * Math.pow(v, 1 / 2.4) - 0.055) * 255 + 0.5);
    }

    private static int encodeDC(float[] value) {
        return (linearToSRGB(value[0]) << 16) + (linearToSRGB(value[1]) << 8) + linearToSRGB(value[2]);
    }

    private static int encodeAC(float[] value, float maximumValue) {
        final var quantR = quantiseAC(value[0] / maximumValue);
        final var quantG = quantiseAC(value[1] / maximumValue);
        final var quantB = quantiseAC(value[2] / maximumValue);
        return quantR * 19 * 19 + quantG * 19 + quantB;
    }

    private static int quantiseAC(float value) {
        final var signPow = (float) (Math.signum(value) * Math.pow(Math.abs(value), 0.5));
        return (int) Math.max(0, Math.min(18, Math.floor(signPow * 9 + 9.5)));
    }

    private static String encode83(int value, int length) {
        final var result = new StringBuilder();
        for (var i = 1; i <= length; i++) {
            final var digit = value / pow83(length - i) % 83;
            result.append(CHARSET.charAt(digit));
        }
        return result.toString();
    }

    private static int pow83(int exponent) {
        var result = 1;
        for (var i = 0; i < exponent; i++) {
            result *= 83;
        }
        return result;
    }
}
