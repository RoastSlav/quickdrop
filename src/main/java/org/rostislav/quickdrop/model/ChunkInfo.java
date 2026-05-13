package org.rostislav.quickdrop.model;

import java.io.File;

/**
 * Metadata for a single chunk of a multi-part file upload.
 *
 * <p>Passed into {@link org.rostislav.quickdrop.service.AsyncFileMergeService} after
 * a chunk has been written to the temporary directory. The merge task dequeues
 * these in order and streams each chunk file into the final output.
 */
public class ChunkInfo {
    /**
     * Zero-based index of this chunk within the overall upload.
     */
    public int chunkNumber;

    /** Temporary file on disk containing this chunk's raw bytes. */
    public File chunkFile;

    /** {@code true} when this is the final chunk of the upload. */
    public boolean isLastChunk;

    public ChunkInfo() {
    }

    /**
     * @param chunkNumber zero-based index of this chunk
     * @param chunkFile   temporary file holding the chunk data
     * @param isLastChunk whether this is the last chunk to be merged
     */
    public ChunkInfo(int chunkNumber, File chunkFile, boolean isLastChunk) {
        this.chunkNumber = chunkNumber;
        this.chunkFile = chunkFile;
        this.isLastChunk = isLastChunk;
    }
}
