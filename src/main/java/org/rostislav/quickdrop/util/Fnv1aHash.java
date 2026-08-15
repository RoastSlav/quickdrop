package org.rostislav.quickdrop.util;

/**
 * FNV-1a 64-bit string hash, used to compress reputation-feed entries (domains, URLs) from
 * ~140 bytes per {@code String} down to 8 raw bytes each — see
 * {@link org.rostislav.quickdrop.service.AbstractHashFeedProvider} for why that matters at
 * feed sizes in the hundreds of thousands of entries.
 *
 * <p>Not cryptographic — collisions are cheap to find deliberately. That is fine here: a
 * tier-1 hash hit is always confirmed against the real feed text before anything is blocked
 * (see {@link org.rostislav.quickdrop.service.AbstractHashFeedProvider#tier2Confirm}), so a
 * collision can only ever cost an extra file scan, never a false block.
 */
public final class Fnv1aHash {
    private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    private Fnv1aHash() {
    }

    public static long hash64(String value) {
        long hash = OFFSET_BASIS;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= PRIME;
        }
        return hash;
    }
}
