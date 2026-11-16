/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Explicit CLI adapter for already-produced analyzer XML, SARIF or Semgrep JSON. Never executes
 * Checkstyle/PMD, obtains rulesets, rewrites sources or changes Maven phases.
 *
 * <p>Six arguments: format status projectRoot originalReport output.tsv
 * output.sha256. The producing plugin must attest status from the original
 * invocation; this CLI cannot establish it from a report alone.
 */
public final class NormalizeCli {
    private NormalizeCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            throw new IllegalArgumentException(
                    "usage: CHECKSTYLE|PMD|SPOTBUGS|CPD|SARIF|SEMGREP COMPLETE|PARTIAL|FAILED root input.report output.tsv digest.sha256");
        }
        AnalyzerXmlNormalizer.Format format = AnalyzerXmlNormalizer.Format.valueOf(args[0]);
        AnalyzerXmlNormalizer.Status status = AnalyzerXmlNormalizer.Status.valueOf(args[1]);
        Path root = Path.of(args[2]).toAbsolutePath().normalize();
        Path original = Path.of(args[3]).toAbsolutePath().normalize();
        Path normalized = Path.of(args[4]).toAbsolutePath().normalize();
        Path digestFile = Path.of(args[5]).toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !original.startsWith(root)
                || !Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)
                || Files.size(original) > AnalyzerXmlNormalizer.MAX_BYTES) {
            throw new IOException("original analyzer report is not a bounded regular project file");
        }
        if (original.equals(normalized) || original.equals(digestFile) || normalized.equals(digestFile)) {
            throw new IOException("original and generated report paths must be distinct");
        }
        byte[] xml = Files.readAllBytes(original);
        AnalyzerXmlNormalizer.Result report = AnalyzerXmlNormalizer.normalize(format, status, root, xml);
        validateOutput(root, normalized);
        validateOutput(root, digestFile);
        // Publish the original adapter output first, then the matching digest
        // sidecar. If the latter fails, consumers see no valid new hash.
        writeAtomically(normalized, report.bytes());
        byte[] hash = (report.sha256() + "\n").getBytes(StandardCharsets.US_ASCII);
        writeAtomically(digestFile, hash);
        System.out.println("M3 analyzer adapter wrote " + report.count() + " findings; sha256=" + report.sha256());
    }

    private static void validateOutput(Path root, Path output) throws IOException {
        Objects.requireNonNull(output, "output");
        if (output.equals(root) || !output.startsWith(root)) {
            throw new IOException("M3 analyzer output must stay within the approved project root");
        }
        Path parent = output.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(output) || Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("output parent must already exist and must not be a symlink");
        }
        for (Path at = parent; at != null && at.startsWith(root); at = at.getParent()) {
            if (Files.isSymbolicLink(at)) {
                throw new IOException("symlinked output ancestor refused");
            }
        }
    }

    private static void writeAtomically(Path output, byte[] data) throws IOException {
        Path parent = output.getParent();
        Path temp = Files.createTempFile(parent, ".m3-analysis-", ".tmp");
        try {
            Files.write(temp, data, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temp, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
