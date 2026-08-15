package org.asamk.signal.manager.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads width/height out of video container headers (MP4/QuickTime "moov" atoms, WebM/Matroska "Tracks"
 * elements) without decoding any video frames. Mirrors the failure posture of the image dimension probe in
 * {@link AttachmentUtils}: any parse problem degrades to unknown (0x0) dimensions rather than throwing or
 * blocking the send, and only a small, bounded amount of the file is ever buffered into memory.
 */
final class MediaDimensionProbe {

    /**
     * Cap on how many bytes we're willing to read while hunting for a video's metadata container (moov/Tracks),
     * and on how large that container's body may be once found. Video header metadata is normally a few KB to a
     * few hundred KB; this is generous headroom without risking unbounded memory use on a hostile or malformed
     * file. For a random-access source (a real file, the common case since attachments are read from disk) this
     * only bounds the *buffered* metadata - skipping over unrelated boxes (e.g. a multi-gigabyte "mdat") is a
     * cheap seek, never a read. For a forward-only stream, it also bounds how much of the stream gets buffered
     * in memory to allow reconstructing it afterwards.
     */
    static final int MAX_HEADER_PROBE_BYTES = 4 * 1024 * 1024;

    private static final long MP4_FIXED_POINT_ONE = 0x10000L;

    private MediaDimensionProbe() {
    }

    record Dimensions(int width, int height) {
        static final Dimensions UNKNOWN = new Dimensions(0, 0);
    }

    record BufferedResult(InputStream stream, int width, int height) {
    }

    static boolean isProbeableVideo(String contentType) {
        return isMp4Like(contentType) || isWebmLike(contentType);
    }

    private static boolean isMp4Like(String contentType) {
        return "video/mp4".equals(contentType) || "video/quicktime".equals(contentType) || "video/x-m4v".equals(
                contentType);
    }

    private static boolean isWebmLike(String contentType) {
        return "video/webm".equals(contentType) || "video/x-matroska".equals(contentType);
    }

    /**
     * Probes a random-access file for its video dimensions. The channel's position is left wherever the probe
     * happened to stop; callers must reset it themselves (mirrors the existing image probe's contract).
     */
    static Dimensions probeFromChannel(String contentType, FileChannel channel) throws IOException {
        return probe(contentType, new ChannelByteSource(channel));
    }

    /**
     * Probes a forward-only stream for its video dimensions, buffering only what it reads (capped) so the
     * stream can be fully reconstructed afterwards regardless of whether the probe found anything. Never
     * throws: any failure degrades to unknown dimensions with the stream left fully intact.
     */
    static BufferedResult probeFromStream(String contentType, InputStream stream) {
        final var source = new BufferingByteSource(stream, MAX_HEADER_PROBE_BYTES);
        Dimensions dimensions;
        try {
            dimensions = probe(contentType, source);
        } catch (IOException | RuntimeException e) {
            dimensions = Dimensions.UNKNOWN;
        }
        return new BufferedResult(source.rebuildFullStream(), dimensions.width(), dimensions.height());
    }

    private static Dimensions probe(String contentType, ByteSource source) throws IOException {
        if (isMp4Like(contentType)) {
            return probeMp4(source);
        }
        if (isWebmLike(contentType)) {
            return probeWebm(source);
        }
        return Dimensions.UNKNOWN;
    }

    // -----------------------------------------------------------------------------------------------------------
    // MP4 / QuickTime (ISO BMFF)
    // -----------------------------------------------------------------------------------------------------------

    private static Dimensions probeMp4(ByteSource source) throws IOException {
        while (true) {
            final byte[] header;
            try {
                header = source.readFully(8);
            } catch (EOFException e) {
                return Dimensions.UNKNOWN;
            }
            long boxSize = Integer.toUnsignedLong(readInt(header, 0));
            final var boxType = fourCC(header, 4);
            final long bodySize;
            if (boxSize == 1) {
                final var extended = source.readFully(8);
                bodySize = readLong(extended, 0) - 16;
            } else if (boxSize == 0) {
                // Box extends to EOF. In practice that's only ever a trailing "mdat" - "moov" always carries an
                // explicit size - so there's nothing useful left to look for.
                return Dimensions.UNKNOWN;
            } else {
                bodySize = boxSize - 8;
            }
            if (bodySize < 0) {
                return Dimensions.UNKNOWN; // malformed box: declared size smaller than its own header
            }
            if ("moov".equals(boxType)) {
                if (bodySize > MAX_HEADER_PROBE_BYTES) {
                    return Dimensions.UNKNOWN;
                }
                return parseMoov(source.readFully((int) bodySize));
            }
            source.skip(bodySize);
        }
    }

    private static Dimensions parseMoov(byte[] moov) {
        for (final var box : readBoxes(moov, 0, moov.length)) {
            if (!"trak".equals(box.type())) {
                continue;
            }
            final var dimensions = parseTrak(moov, box.bodyOffset(), box.bodyLength());
            if (dimensions != null) {
                return dimensions;
            }
        }
        return Dimensions.UNKNOWN;
    }

    private static Dimensions parseTrak(byte[] data, int offset, int length) {
        Box tkhd = null;
        var isVideoTrack = false;
        for (final var box : readBoxes(data, offset, length)) {
            if ("tkhd".equals(box.type())) {
                tkhd = box;
            } else if ("mdia".equals(box.type())) {
                isVideoTrack = isVideoTrack || hasVideoHandler(data, box.bodyOffset(), box.bodyLength());
            }
        }
        if (!isVideoTrack || tkhd == null) {
            return null; // not a video track (or malformed) - let the caller try the next trak
        }
        return parseTkhd(data, tkhd.bodyOffset(), tkhd.bodyLength());
    }

    private static boolean hasVideoHandler(byte[] data, int offset, int length) {
        for (final var box : readBoxes(data, offset, length)) {
            if (!"hdlr".equals(box.type())) {
                continue;
            }
            // hdlr body: version(1) + flags(3) + pre_defined(4) + handler_type(4) + ...
            return box.bodyLength() >= 12 && "vide".equals(fourCC(data, box.bodyOffset() + 8));
        }
        return false;
    }

    private static Dimensions parseTkhd(byte[] data, int offset, int length) {
        // Width/height are always the last 8 bytes of the box (32-bit 16.16 fixed point), regardless of whether
        // it's the 32-bit (version 0) or 64-bit (version 1) field layout further up.
        if (length < 8) {
            return Dimensions.UNKNOWN;
        }
        var width = (int) (Integer.toUnsignedLong(readInt(data, offset + length - 8)) >> 16);
        var height = (int) (Integer.toUnsignedLong(readInt(data, offset + length - 4)) >> 16);
        if (width <= 0 || height <= 0) {
            return Dimensions.UNKNOWN;
        }
        // The 3x3 transformation matrix sits right before width/height (36 bytes: 9 x 32-bit fixed point).
        // Phone-shot portrait video is stored as landscape samples with a 90deg/270deg rotation matrix, so the
        // *displayed* aspect ratio needs the axes swapped.
        final var matrixOffset = offset + length - 8 - 36;
        if (matrixOffset >= offset) {
            final var a = readInt(data, matrixOffset);
            final var b = readInt(data, matrixOffset + 4);
            final var c = readInt(data, matrixOffset + 12);
            final var d = readInt(data, matrixOffset + 16);
            final var rotated90or270 =
                    (a == 0 && b == MP4_FIXED_POINT_ONE && c == -MP4_FIXED_POINT_ONE && d == 0) || (a == 0
                            && b == -MP4_FIXED_POINT_ONE
                            && c == MP4_FIXED_POINT_ONE
                            && d == 0);
            if (rotated90or270) {
                final var swap = width;
                width = height;
                height = swap;
            }
        }
        return new Dimensions(width, height);
    }

    private record Box(String type, int bodyOffset, int bodyLength) {
    }

    private static List<Box> readBoxes(byte[] data, int offset, int length) {
        final List<Box> boxes = new ArrayList<>();
        var pos = offset;
        final var end = offset + length;
        while (pos + 8 <= end) {
            long boxSize = Integer.toUnsignedLong(readInt(data, pos));
            final var type = fourCC(data, pos + 4);
            var headerSize = 8;
            if (boxSize == 1) {
                if (pos + 16 > end) {
                    break;
                }
                boxSize = readLong(data, pos + 8);
                headerSize = 16;
            } else if (boxSize == 0) {
                boxSize = end - pos;
            }
            if (boxSize < headerSize || pos + boxSize > end) {
                break; // malformed/truncated - stop, return whatever boxes were already found
            }
            boxes.add(new Box(type, pos + headerSize, (int) (boxSize - headerSize)));
            pos += boxSize;
        }
        return boxes;
    }

    // -----------------------------------------------------------------------------------------------------------
    // WebM / Matroska (EBML)
    // -----------------------------------------------------------------------------------------------------------

    private static final long EBML_ID_SEGMENT = 0x18538067L;
    private static final long EBML_ID_TRACKS = 0x1654AE6BL;
    private static final long EBML_ID_TRACK_ENTRY = 0xAEL;
    private static final long EBML_ID_TRACK_TYPE = 0x83L;
    private static final long EBML_ID_VIDEO = 0xE0L;
    private static final long EBML_ID_PIXEL_WIDTH = 0xB0L;
    private static final long EBML_ID_PIXEL_HEIGHT = 0xBAL;
    private static final int EBML_TRACK_TYPE_VIDEO = 1;

    private static final long CHILD_NOT_FOUND = -1;
    private static final long CHILD_SIZE_UNKNOWN = -2;

    private static Dimensions probeWebm(ByteSource source) throws IOException {
        // Segment is commonly written with an unknown/streaming size (e.g. live browser recordings), so we
        // don't try to skip past it - we just need to know it's there, then keep reading forward through its
        // children looking for Tracks.
        if (seekToChild(source, EBML_ID_SEGMENT) == CHILD_NOT_FOUND) {
            return Dimensions.UNKNOWN;
        }
        final var tracksSize = seekToChild(source, EBML_ID_TRACKS);
        if (tracksSize < 0 || tracksSize > MAX_HEADER_PROBE_BYTES) {
            return Dimensions.UNKNOWN;
        }
        return parseTracks(source.readFully((int) tracksSize));
    }

    /**
     * Reads sibling EBML elements forward from the source's current position until one with {@code targetId} is
     * found (leaving the source positioned at the start of its body), skipping past every other sibling by its
     * declared size. Returns the found element's body size, {@link #CHILD_SIZE_UNKNOWN} if it declared an
     * unknown/streaming size, or {@link #CHILD_NOT_FOUND} if the source ran out of data or a non-matching
     * sibling had an unknown size (which makes it impossible to skip over).
     */
    private static long seekToChild(ByteSource source, long targetId) throws IOException {
        while (true) {
            final Vint id;
            try {
                id = readVint(source, true);
            } catch (EOFException e) {
                return CHILD_NOT_FOUND;
            }
            final var size = readVint(source, false);
            if (id.value() == targetId) {
                return size.unknown() ? CHILD_SIZE_UNKNOWN : size.value();
            }
            if (size.unknown()) {
                return CHILD_NOT_FOUND;
            }
            source.skip(size.value());
        }
    }

    private static Dimensions parseTracks(byte[] tracks) {
        for (final var element : readEbmlElements(tracks, 0, tracks.length)) {
            if (element.id() != EBML_ID_TRACK_ENTRY) {
                continue;
            }
            final var dimensions = parseTrackEntry(tracks, element.bodyOffset(), element.bodyLength());
            if (dimensions != null) {
                return dimensions;
            }
        }
        return Dimensions.UNKNOWN;
    }

    private static Dimensions parseTrackEntry(byte[] data, int offset, int length) {
        var isVideo = false;
        EbmlElement video = null;
        for (final var child : readEbmlElements(data, offset, length)) {
            if (child.id() == EBML_ID_TRACK_TYPE && child.bodyLength() >= 1) {
                isVideo = readUnsignedEbmlInt(data, child.bodyOffset(), child.bodyLength()) == EBML_TRACK_TYPE_VIDEO;
            } else if (child.id() == EBML_ID_VIDEO) {
                video = child;
            }
        }
        if (!isVideo || video == null) {
            return null; // not a video track (or malformed) - let the caller try the next TrackEntry
        }
        Integer width = null;
        Integer height = null;
        for (final var child : readEbmlElements(data, video.bodyOffset(), video.bodyLength())) {
            if (child.id() == EBML_ID_PIXEL_WIDTH) {
                width = (int) readUnsignedEbmlInt(data, child.bodyOffset(), child.bodyLength());
            } else if (child.id() == EBML_ID_PIXEL_HEIGHT) {
                height = (int) readUnsignedEbmlInt(data, child.bodyOffset(), child.bodyLength());
            }
        }
        if (width == null || height == null || width <= 0 || height <= 0) {
            return null;
        }
        return new Dimensions(width, height);
    }

    private record EbmlElement(long id, int bodyOffset, int bodyLength) {
    }

    private static List<EbmlElement> readEbmlElements(byte[] data, int offset, int length) {
        final List<EbmlElement> elements = new ArrayList<>();
        final var source = new ByteArraySource(data, offset, length);
        while (true) {
            final Vint id;
            final Vint size;
            try {
                id = readVint(source, true);
                size = readVint(source, false);
            } catch (IOException e) {
                break; // ran out of bytes / malformed - stop, return whatever elements were already found
            }
            final var remaining = source.remaining();
            final var bodyLength = size.unknown() || size.value() > remaining ? remaining : (int) size.value();
            elements.add(new EbmlElement(id.value(), source.position(), bodyLength));
            try {
                source.skip(bodyLength);
            } catch (IOException e) {
                break;
            }
        }
        return elements;
    }

    private static long readUnsignedEbmlInt(byte[] data, int offset, int length) {
        long value = 0;
        for (var i = 0; i < length; i++) {
            value = (value << 8) | (data[offset + i] & 0xFF);
        }
        return value;
    }

    private record Vint(long value, int length, boolean unknown) {
    }

    /**
     * Reads one EBML variable-length integer. Element IDs keep their length-marker bits as part of the value
     * ({@code keepMarker=true}); element sizes have the marker stripped, with an all-ones value denoting an
     * unknown/streaming size.
     */
    private static Vint readVint(ByteSource source, boolean keepMarker) throws IOException {
        final var first = source.readFully(1)[0] & 0xFF;
        if (first == 0) {
            throw new EOFException("invalid EBML VINT (leading byte 0x00)");
        }
        final var length = Integer.numberOfLeadingZeros(first) - 24 + 1;
        var value = keepMarker ? (long) first : (long) (first & (0xFF >>> length));
        if (length > 1) {
            for (final var b : source.readFully(length - 1)) {
                value = (value << 8) | (b & 0xFF);
            }
        }
        final var unknown = !keepMarker && value == (1L << (7L * length)) - 1;
        return new Vint(value, length, unknown);
    }

    // -----------------------------------------------------------------------------------------------------------
    // Shared byte-reading helpers
    // -----------------------------------------------------------------------------------------------------------

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16) | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static long readLong(byte[] data, int offset) {
        return (Integer.toUnsignedLong(readInt(data, offset)) << 32) | Integer.toUnsignedLong(readInt(data,
                offset + 4));
    }

    private static String fourCC(byte[] data, int offset) {
        return new String(data, offset, 4, StandardCharsets.US_ASCII);
    }

    /**
     * A minimal source of sequential bytes that also supports skipping forward. Lets the box/EBML walkers above
     * run unmodified over a random-access file, a forward-only stream, or an in-memory buffer.
     */
    private interface ByteSource {

        byte[] readFully(int length) throws IOException;

        void skip(long length) throws IOException;
    }

    private static final class ChannelByteSource implements ByteSource {

        private final FileChannel channel;

        private ChannelByteSource(FileChannel channel) {
            this.channel = channel;
        }

        @Override
        public byte[] readFully(int length) throws IOException {
            final var buffer = ByteBuffer.allocate(length);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    throw new EOFException("Unexpected end of file while probing video dimensions");
                }
            }
            return buffer.array();
        }

        @Override
        public void skip(long length) throws IOException {
            channel.position(channel.position() + length);
        }
    }

    /**
     * Wraps a forward-only stream, retaining every byte it reads (or skips - skipping still has to consume the
     * stream, since there's no way to seek back over it later) so the full content can be reconstructed
     * afterwards regardless of where probing stopped. Bounded by {@code cap} total bytes.
     */
    private static final class BufferingByteSource implements ByteSource {

        private final InputStream stream;
        private final int cap;
        private final ByteArrayOutputStream buffered = new ByteArrayOutputStream();

        private BufferingByteSource(InputStream stream, int cap) {
            this.stream = stream;
            this.cap = cap;
        }

        @Override
        public byte[] readFully(int length) throws IOException {
            if (buffered.size() + length > cap) {
                throw new IOException("Video header probe exceeded " + cap + " byte budget");
            }
            final var buf = new byte[length];
            var read = 0;
            try {
                while (read < length) {
                    final var n = stream.read(buf, read, length - read);
                    if (n < 0) {
                        throw new EOFException("Unexpected end of stream while probing video dimensions");
                    }
                    read += n;
                }
            } finally {
                // Whatever we actually consumed from the stream must be retained even on failure - it's gone
                // from `stream` either way, and dropping it here would corrupt the reconstructed attachment.
                if (read > 0) {
                    buffered.write(buf, 0, read);
                }
            }
            return buf;
        }

        @Override
        public void skip(long length) throws IOException {
            var remaining = length;
            while (remaining > 0) {
                final var chunk = (int) Math.min(remaining, 8192);
                readFully(chunk);
                remaining -= chunk;
            }
        }

        InputStream rebuildFullStream() {
            return new SequenceInputStream(new ByteArrayInputStream(buffered.toByteArray()), stream);
        }
    }

    private static final class ByteArraySource implements ByteSource {

        private final byte[] data;
        private final int end;
        private int pos;

        private ByteArraySource(byte[] data, int offset, int length) {
            this.data = data;
            this.pos = offset;
            this.end = offset + length;
        }

        @Override
        public byte[] readFully(int length) throws IOException {
            if (pos + length > end) {
                throw new EOFException("Unexpected end of buffer while probing video dimensions");
            }
            final var result = new byte[length];
            System.arraycopy(data, pos, result, 0, length);
            pos += length;
            return result;
        }

        @Override
        public void skip(long length) throws IOException {
            if (pos + length > end) {
                throw new EOFException("Unexpected end of buffer while probing video dimensions");
            }
            pos += (int) length;
        }

        int position() {
            return pos;
        }

        int remaining() {
            return end - pos;
        }
    }
}
