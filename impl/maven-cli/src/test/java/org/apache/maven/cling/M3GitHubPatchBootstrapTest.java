// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Hitesh Soliwal and contributors
package org.apache.maven.cling;

import org.junit.jupiter.api.Test;

/** Run the same exact Java 17 regression suite inside Maven's original JUnit lane. */
class M3GitHubPatchBootstrapTest {
    @Test
    void pinnedGithubPatchPreflightAndRefusalContracts() throws Exception {
        M3GitHubPatchBootstrapSelfTest.main(new String[0]);
    }
}
