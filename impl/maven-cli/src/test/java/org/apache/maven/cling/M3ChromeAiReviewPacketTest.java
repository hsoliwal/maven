// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Hitesh Soliwal and contributors
package org.apache.maven.cling;

import org.junit.jupiter.api.Test;

/** Run the exact Java 17 on-device-review controls in Maven's original JUnit lane. */
class M3ChromeAiReviewPacketTest {
    @Test
    void immutableChromeReviewPacketAndRefusal() throws Exception {
        M3ChromeAiReviewPacketSelfTest.main(new String[0]);
    }
}
