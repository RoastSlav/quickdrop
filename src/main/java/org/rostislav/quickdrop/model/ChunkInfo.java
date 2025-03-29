package org.rostislav.quickdrop.model;

import java.io.File;

public class ChunkInfo {
    public int chunkNumber;
    public File chunkFile;
    public boolean isLastChunk;

    public ChunkInfo() {

    }

    public ChunkInfo(int chunkNumber, File chunkFile, boolean isLastChunk) {
        this.chunkNumber = chunkNumber;
        this.chunkFile = chunkFile;
        this.isLastChunk = isLastChunk;
    }
}