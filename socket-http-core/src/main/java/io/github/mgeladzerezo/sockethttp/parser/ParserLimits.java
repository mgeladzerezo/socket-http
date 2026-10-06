package io.github.mgeladzerezo.sockethttp.parser;

/**
 * Size limits the parser enforces while reading, before anything is handed to a handler.
 *
 * @param maxRequestLineLength bytes in the request line, excluding CRLF; beyond it 414
 * @param maxHeaderSectionSize bytes in all header lines together (and, separately, in the
 *                             trailer section); beyond it 431
 * @param maxHeaderCount       number of header fields; beyond it 431
 * @param maxBodySize          bytes of decoded request content; beyond it 413
 * @param maxChunkLineLength   bytes in a chunk-size line including extensions; beyond it 400
 */
public record ParserLimits(int maxRequestLineLength,
                           int maxHeaderSectionSize,
                           int maxHeaderCount,
                           long maxBodySize,
                           int maxChunkLineLength) {

    /** 8 KiB request line, 16 KiB of headers in at most 100 fields, 8 MiB bodies. */
    public static final ParserLimits DEFAULT = new ParserLimits(8192, 16384, 100, 8L * 1024 * 1024, 256);

    public ParserLimits {
        if (maxRequestLineLength < 16 || maxHeaderSectionSize < 16 || maxHeaderCount < 1
                || maxBodySize < 0 || maxChunkLineLength < 16) {
            throw new IllegalArgumentException("parser limits too small: " + maxRequestLineLength + ", "
                    + maxHeaderSectionSize + ", " + maxHeaderCount + ", " + maxBodySize + ", " + maxChunkLineLength);
        }
        if (maxBodySize > Integer.MAX_VALUE - 16) {
            throw new IllegalArgumentException("bodies are buffered in memory; maxBodySize must fit in an int");
        }
    }

    public ParserLimits withMaxBodySize(long bytes) {
        return new ParserLimits(maxRequestLineLength, maxHeaderSectionSize, maxHeaderCount, bytes, maxChunkLineLength);
    }

    public ParserLimits withMaxRequestLineLength(int bytes) {
        return new ParserLimits(bytes, maxHeaderSectionSize, maxHeaderCount, maxBodySize, maxChunkLineLength);
    }

    public ParserLimits withMaxHeaderSectionSize(int bytes) {
        return new ParserLimits(maxRequestLineLength, bytes, maxHeaderCount, maxBodySize, maxChunkLineLength);
    }

    public ParserLimits withMaxHeaderCount(int count) {
        return new ParserLimits(maxRequestLineLength, maxHeaderSectionSize, count, maxBodySize, maxChunkLineLength);
    }
}
