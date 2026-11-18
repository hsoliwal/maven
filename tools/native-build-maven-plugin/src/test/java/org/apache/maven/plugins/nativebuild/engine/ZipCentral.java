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
package org.apache.maven.plugins.nativebuild.engine;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Reads the Unix modes a zip's central directory records, independently of the code under test. */
final class ZipCentral {
    private static final int CEN = 0x02014b50;

    private ZipCentral() {}

    /** The mode of each central directory entry, in order; 0 when the entry was not made on Unix. */
    static List<Integer> modes(byte[] zip) {
        ByteBuffer b = ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN);
        List<Integer> modes = new ArrayList<>();
        int at = 0;
        while (at + 46 <= zip.length) {
            if (b.getInt(at) != CEN) {
                at++;
                continue;
            }
            boolean unix = (zip[at + 5] & 0xff) == 3;
            modes.add(unix ? (b.getInt(at + 38) >>> 16) & 07777 : 0);
            at += 46 + (b.getShort(at + 28) & 0xffff) + (b.getShort(at + 30) & 0xffff) + (b.getShort(at + 32) & 0xffff);
        }
        return modes;
    }
}
