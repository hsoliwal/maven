// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Validates input binding only. Producer provenance is data, never executable authority. */
final class CanonicalPreparationEvidence {
    private static final String PREPARE = "com.synexia.rewrite.M3PreparePrivatePrimitiveAtomsRecipe";
    private static final String PATTERN = "com.synexia.rewrite.M3JavaPatternInventoryRecipe";
    private static final Set<String> PATTERNS = Set.of("ITERATION", "BRANCHING", "EXCEPTION_BOUNDARY",
            "SYNCHRONIZATION", "STREAM_PIPELINE", "LAMBDA", "TYPE_TEST", "OPTIONAL_FLOW", "BUILDER_CANDIDATE",
            "FACTORY_CANDIDATE", "TERNARY", "SHORT_CIRCUIT", "MEMBER_REFERENCE", "STATE_WRITE", "RESOURCE_SCOPE",
            "STRAIGHT_LINE", "PLAIN_TYPE", "INHERITANCE", "INTERFACE_IMPLEMENTATION", "RECORD", "SEALED_HIERARCHY", "ANNOTATED_TYPE");

    static String inspect(Path base, Path sourceRoot, Path receipt) throws IOException {
        Path realBase = base.toRealPath();
        check(receipt.startsWith(base) && !receipt.startsWith(sourceRoot) && receipt.toString().endsWith(".json"));
        Path component = base;
        for (Path segment : base.relativize(receipt)) {
            component = component.resolve(segment);
            check(!Files.isSymbolicLink(component));
        }
        check(Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS) && receipt.toRealPath().startsWith(realBase));
        check(Files.size(receipt) <= 1_048_576);
        byte[] bytes;
        try (var input = Files.newInputStream(receipt)) { bytes = input.readNBytes(1_048_577); }
        check(bytes.length <= 1_048_576);
        String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        Map<?, ?> report = object(TinyJson.parse(json));
        fields(report, "schema", "status", "sourceRoot", "sourceDigest", "sourceFiles", "sourceBytes", "files",
                "recipes", "atomCandidateFiles", "proposedPrivateHelpers", "patternRowCount", "patternCounts",
                "unsupportedFiles", "executionProvenance", "remainingStages");
        check("synexia.smart-build.preparation.v1".equals(report.get("schema")) && "COMPLETE".equals(report.get("status")));
        SourceInventory.Selected selected = SourceInventory.inspectSelected(base, sourceRoot);
        check(selected.summary().files() > 0 && selected.sourceRoot().equals(report.get("sourceRoot"))
                && selected.summary().digest().equals(hash(report.get("sourceDigest")))
                && selected.summary().files() == number(report.get("sourceFiles"), 2000)
                && selected.summary().bytes() == number(report.get("sourceBytes"), 16_000_000)
                && number(report.get("unsupportedFiles"), 2000) == 0);
        List<?> files = array(report.get("files"));
        check(files.size() == selected.files().size());
        long helpers = 0, candidates = 0, rows = 0;
        Map<String, Long> counts = new TreeMap<>();
        for (int i = 0; i < files.size(); i++) {
            Map<?, ?> file = object(files.get(i));
            fields(file, "path", "sha256", "bytes", "kind", "status", "reason", "atomPreparation", "patterns");
            SourceInventory.FileFact actual = selected.files().get(i);
            check(actual.path().equals(file.get("path")) && actual.path().endsWith(".java")
                    && actual.sha256().equals(hash(file.get("sha256"))) && actual.bytes() == number(file.get("bytes"), 1_048_576)
                    && "JAVA".equals(file.get("kind")) && "COMPLETE".equals(file.get("status")));
            Map<?, ?> atom = object(file.get("atomPreparation"));
            fields(atom, "status", "candidateSha256", "proposedHelpers");
            long proposed = number(atom.get("proposedHelpers"), 100_000);
            String candidateHash = hash(atom.get("candidateSha256"));
            String status = string(atom.get("status"), 256);
            if (proposed == 0) check(status.startsWith("UNCHANGED:NO_SUPPORTED_EXTRACTION:")
                    && actual.sha256().equals(candidateHash) && "NO_SUPPORTED_EXTRACTION".equals(file.get("reason")));
            else check(status.startsWith("UNPROVEN:") && !actual.sha256().equals(candidateHash)
                    && "UNPROVEN_PRIVATE_HELPER_PROPOSAL".equals(file.get("reason")));
            helpers += proposed;
            if (proposed > 0) candidates++;
            List<?> patterns = array(file.get("patterns"));
            for (Object value : patterns) {
                Map<?, ?> pattern = object(value);
                fields(pattern, "ownerKind", "owner", "pattern", "exactSha256", "structuralSha256", "authority");
                check(Set.of("TYPE", "METHOD").contains(string(pattern.get("ownerKind"), 32)));
                string(pattern.get("owner"), 4096);
                String category = string(pattern.get("pattern"), 80);
                check(PATTERNS.contains(category) && "CANDIDATE_ONLY".equals(pattern.get("authority")));
                hash(pattern.get("exactSha256")); hash(pattern.get("structuralSha256"));
                counts.merge(category, 1L, Long::sum);
                rows++;
            }
        }
        check(helpers == number(report.get("proposedPrivateHelpers"), 200_000_000)
                && candidates == number(report.get("atomCandidateFiles"), 2000)
                && rows == number(report.get("patternRowCount"), 1_000_000));
        Map<?, ?> declaredCounts = object(report.get("patternCounts"));
        check(declaredCounts.keySet().equals(counts.keySet()));
        for (var count : counts.entrySet()) check(count.getValue() == number(declaredCounts.get(count.getKey()), 1_000_000));
        List<?> recipes = array(report.get("recipes"));
        check(recipes.size() == 2);
        for (int i = 0; i < recipes.size(); i++) {
            Map<?, ?> recipe = object(recipes.get(i));
            fields(recipe, "name", "status", "executedFiles", "rows");
            check((i == 0 ? PREPARE : PATTERN).equals(recipe.get("name")) && "EXECUTED".equals(recipe.get("status"))
                    && selected.summary().files() == number(recipe.get("executedFiles"), 2000)
                    && (i == 0 ? selected.summary().files() : rows) == number(recipe.get("rows"), 1_000_000));
        }
        Map<?, ?> provenance = object(report.get("executionProvenance"));
        fields(provenance, "runner", "ownerRevision", "ownerSources", "javaRuntime", "execution",
                "sourceMutationAuthority", "promotionAuthority", "physicalExtractionExecuted");
        check("com.synexia.rewrite.ChromeLlmRecipeExecution".equals(provenance.get("runner"))
                && "OPENREWRITE_SDK".equals(provenance.get("execution"))
                && string(provenance.get("ownerRevision"), 40).matches("[0-9a-f]{40}")
                && Boolean.FALSE.equals(provenance.get("sourceMutationAuthority"))
                && Boolean.FALSE.equals(provenance.get("promotionAuthority"))
                && Boolean.FALSE.equals(provenance.get("physicalExtractionExecuted")));
        string(provenance.get("javaRuntime"), 80);
        Map<?, ?> owners = object(provenance.get("ownerSources"));
        check(!owners.isEmpty() && owners.size() <= 32);
        for (var owner : owners.entrySet()) { string(owner.getKey(), 256); hash(owner.getValue()); }
        check(array(report.get("remainingStages")).equals(List.of("com.synexia.rewrite.M3AtomizeRecipe", "com.synexia.rewrite.M3PatternizeRecipe")));
        // Check the selected bytes again after validating all receipt rows, before any provider call.
        check(selected.summary().digest().equals(SourceInventory.inspectSelected(base, sourceRoot).summary().digest()));
        StringBuilder categories = new StringBuilder("{");
        for (var entry : counts.entrySet()) {
            if (categories.length() > 1) categories.append(',');
            categories.append(TinyJson.quote(entry.getKey())).append(':').append(entry.getValue());
        }
        categories.append('}');
        return "{\"receiptSha256\":" + TinyJson.quote(Hashes.sha256(bytes))
                + ",\"sourceDigest\":" + TinyJson.quote(selected.summary().digest())
                + ",\"selectedFiles\":" + selected.summary().files() + ",\"selectedBytes\":" + selected.summary().bytes()
                + ",\"proposedPrivateHelpers\":" + helpers + ",\"patternRowCount\":" + rows
                + ",\"patternCounts\":" + categories + ",\"executionProvenance\":\"PRODUCER_REPORTED_NOT_REEXECUTED\","
                + "\"behaviorEquivalenceVerified\":false,\"physicalExtractionExecuted\":false,"
                + "\"sourceMutationAuthority\":false,\"promotionAuthority\":false}";
    }

    private static void check(boolean accepted) throws IOException {
        if (!accepted) throw new IOException("preparation receipt refused");
    }
    private static Map<?, ?> object(Object value) throws IOException {
        check(value instanceof Map<?, ?>); return (Map<?, ?>) value;
    }
    private static List<?> array(Object value) throws IOException {
        check(value instanceof List<?>); return (List<?>) value;
    }
    private static void fields(Map<?, ?> value, String... names) throws IOException {
        check(value.keySet().equals(Set.of(names)));
    }
    private static String string(Object value, int limit) throws IOException {
        check(value instanceof String && !((String) value).isBlank() && ((String) value).length() <= limit);
        return (String) value;
    }
    private static String hash(Object value) throws IOException {
        String hash = string(value, 64); check(hash.matches("[0-9a-f]{64}")); return hash;
    }
    private static long number(Object value, long maximum) throws IOException {
        String text = string(value, 12); check(text.matches("0|[1-9][0-9]{0,10}"));
        long number = Long.parseLong(text); check(number <= maximum); return number;
    }
}
