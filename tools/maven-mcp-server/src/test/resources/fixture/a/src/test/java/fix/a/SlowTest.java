/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package fix.a;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Runs only when selected. Writes a counter to target/heartbeat.txt every second for two minutes; the protocol test
 * stops it with a time limit and checks that the counter no longer moves, so no forked JVM outlived the timeout.
 */
public class SlowTest {
    @Test
    public void beats() throws Exception {
        Path beat = Paths.get("target", "heartbeat.txt");
        for (int second = 0; second < 120; second++) {
            Files.write(beat, Integer.toString(second).getBytes(StandardCharsets.US_ASCII));
            Thread.sleep(1000L);
        }
    }
}
