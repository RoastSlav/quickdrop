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
    /** Zero-based. */
    public int chunkNumber;

    public File chunkFile;

    public boolean isLastChunk;

    public ChunkInfo(int chunkNumber, File chunkFile, boolean isLastChunk) {
        this.chunkNumber = chunkNumber;
        this.chunkFile = chunkFile;
        this.isLastChunk = isLastChunk;
    }
}
