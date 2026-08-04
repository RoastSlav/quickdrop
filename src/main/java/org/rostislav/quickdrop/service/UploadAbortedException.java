package org.rostislav.quickdrop.service;

import java.io.IOException;

/**
 * Thrown by {@link AsyncFileMergeService#submitChunk} when a chunk is submitted for an
 * upload that has already been aborted (via {@code POST /api/file/upload-abort} or TTL
 * eviction). Distinct from {@link IOException} so callers can tell this well-understood,
 * expected condition apart from a genuine I/O failure and map it to its own status code
 * instead of a generic 500.
 */
public class UploadAbortedException extends IOException {
    public UploadAbortedException(String message) {
        super(message);
    }
}
