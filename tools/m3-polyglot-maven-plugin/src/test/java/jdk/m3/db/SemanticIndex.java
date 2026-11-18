// SPDX-License-Identifier: Apache-2.0
package jdk.m3.db;

/**
 * Test-only compile dependency for the pinned M3JDK M3DB artifact-store sources.
 * Semantic indexes are outside this storage adapter's contract and are never invoked here.
 */
public final class SemanticIndex {
    public static final String ARTIFACT_KIND = "m3.semantic-index";
    public static final int FORMAT_VERSION = 1;

    public byte[] encode() {
        throw new UnsupportedOperationException("semantic fixture not exercised");
    }

    public static SemanticIndex decode(byte[] bytes) {
        throw new UnsupportedOperationException("semantic fixture not exercised");
    }
}
