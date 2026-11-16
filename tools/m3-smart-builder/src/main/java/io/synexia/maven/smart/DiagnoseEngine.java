// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Deterministic Maven failure facts plus an untrusted advisory candidate. */
public final class DiagnoseEngine {
    private DiagnoseEngine() { }

    public static String diagnose(Path pom, BuildLogFacts log, SourceInventory source, String candidate)
            throws IOException {
        if (candidate == null || candidate.length() > 32768) throw new IOException("candidate exceeds bound");
        PomFacts facts = PomFacts.inspect(pom);
        return "{\n  \"schema\": \"m3.smart.diagnose.v1\",\n"
                + "  \"mode\": \"review-only\",\n"
                + "  \"pomSha256\": " + TinyJson.quote(Hashes.sha256(pom)) + ",\n"
                + "  \"pomFacts\": " + facts.json() + ",\n"
                + "  \"logFacts\": " + log.json() + ",\n"
                + "  \"sourceFacts\": " + source.json() + ",\n"
                + "  \"llmCandidateTrusted\": false,\n"
                + "  \"llmCandidateSha256\": " + TinyJson.quote(Hashes.sha256(candidate.getBytes(StandardCharsets.UTF_8))) + ",\n"
                + "  \"llmCandidate\": " + TinyJson.quote(candidate) + "\n}\n";
    }

    public static String prompt(PomFacts pom, BuildLogFacts log, SourceInventory source) {
        return "M3 build diagnostic advisory only. This is immutable, sanitized aggregate data; "
                + "do not infer raw code or log details, assert build success, issue commands, "
                + "choose artifact URLs or claim tests ran. Explain the most likely error "
                + "categories and required verification gates as prose only. "
                + "POM=" + pom.json() + "; buildLog=" + log.json() + "; source=" + source.json();
    }
}
