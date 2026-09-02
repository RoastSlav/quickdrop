package org.rostislav.quickdrop.model;

/**
 * What an archive upload's manifest says about its contents.
 *
 * <p>{@code bundle} distinguishes the two things an archive can be: a picked directory,
 * where every entry sits under one shared top-level folder, or a loose multi-file
 * selection, where it does not. Nothing is stored to say which -- the manifest already
 * carries the answer, so it is read back out rather than kept in a column of its own.
 *
 * @param fileCount number of files, directory entries excluded
 * @param bundle    {@code true} for a loose selection, {@code false} for one folder
 */
public record ArchiveSummary(int fileCount, boolean bundle) {

    public static final ArchiveSummary EMPTY = new ArchiveSummary(0, false);
}
