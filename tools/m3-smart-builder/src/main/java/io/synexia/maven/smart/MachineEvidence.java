// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Source-bound, producer-reported atom fingerprints. Similarity is never semantic equivalence. */
final class MachineEvidence {
    static final String HEADER = "source_path\tsource_sha256\tatom_id\tstructural_sha256\tlogic_sha256\tsimhash64\tcontract_sha256";
    private static final int MAX_ROWS = 4096;

    private MachineEvidence() { }

    record Summary(String inputSha256, int files, int atoms, int structuralGroups,
                   int logicGroups, long similarPairs) {
        String json() {
            return "{\"schema\":\"m3.smart.machine-evidence.v1\",\"inputSha256\":" + TinyJson.quote(inputSha256)
                    + ",\"sourceBound\":true,\"files\":" + files + ",\"atoms\":" + atoms
                    + ",\"structuralDuplicateGroups\":" + structuralGroups
                    + ",\"logicDuplicateGroups\":" + logicGroups + ",\"simHashNearPairs\":" + similarPairs
                    + ",\"similarityIsProof\":false,\"semanticProofAuthenticated\":false}";
        }
    }

    static Summary inspect(Path base, Path sourceRoot, Path file) throws IOException {
        byte[] bytes = RecipeCatalog.readLocal(base, file, 2_097_152);
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if (text.indexOf('\r') >= 0 || text.indexOf('\0') >= 0 || !text.endsWith("\n"))
            throw new IOException("invalid machine fingerprint ledger encoding");
        String[] rows = text.substring(0, text.length() - 1).split("\n", -1);
        if (rows.length < 2 || rows.length > MAX_ROWS + 1 || !HEADER.equals(rows[0]))
            throw new IOException("invalid machine fingerprint ledger schema");
        var selected = SourceInventory.inspectSelected(base, sourceRoot);
        Map<String, String> sources = new HashMap<>();
        for (SourceInventory.FileFact source : selected.files())
            sources.put(source.path(), source.sha256());
        Set<String> covered = new HashSet<>();
        Map<String, Integer> structural = new HashMap<>();
        Map<String, Integer> logic = new HashMap<>();
        long[] hashes = new long[rows.length - 1];
        String previous = "";
        for (int i = 1; i < rows.length; i++) {
            String[] parts = rows[i].split("\t", -1);
            if (parts.length != 7 || !parts[0].endsWith(".java") || !sources.containsKey(parts[0])
                    || !parts[1].matches("[0-9a-f]{64}") || !parts[2].matches("[a-zA-Z0-9_$#.:/-]{1,128}")
                    || !parts[3].matches("[0-9a-f]{64}") || !parts[4].matches("[0-9a-f]{64}")
                    || !parts[5].matches("[0-9a-f]{16}") || !parts[6].matches("[0-9a-f]{64}"))
                throw new IOException("invalid fingerprint row");
            String key = parts[0] + "\t" + parts[2];
            if (key.compareTo(previous) <= 0 || !parts[1].equals(sources.get(parts[0])))
                throw new IOException("fingerprint ordering or source binding refused");
            previous = key;
            covered.add(parts[0]);
            structural.merge(parts[3], 1, Integer::sum);
            logic.merge(parts[4], 1, Integer::sum);
            hashes[i - 1] = Long.parseUnsignedLong(parts[5], 16);
        }
        if (!covered.equals(sources.keySet())) throw new IOException("incomplete machine source coverage");
        long near = 0;
        for (int i = 0; i < hashes.length; i++)
            for (int j = i + 1; j < hashes.length; j++)
                if (Long.bitCount(hashes[i] ^ hashes[j]) <= 3) near++;
        int structures = (int) structural.values().stream().filter(n -> n > 1).count();
        int logics = (int) logic.values().stream().filter(n -> n > 1).count();
        if (!selected.summary().digest().equals(SourceInventory.inspectSelected(base, sourceRoot).summary().digest()))
            throw new IOException("source changed during fingerprint examination");
        return new Summary(Hashes.sha256(bytes), covered.size(), hashes.length, structures, logics, near);
    }
}
