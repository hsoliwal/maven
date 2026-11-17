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
package org.apache.maven.agent.events;

import java.util.Arrays;
import java.util.List;

/** JSON writer: escaping, truncation and field order. */
public final class JsonTextTestMain {
    private JsonTextTestMain() {}

    public static void main(String[] args) {
        escaping();
        truncation();
        ordering();
        Check.done("JsonTextTestMain");
    }

    private static void escaping() {
        Check.equal("\"a\\\"b\\\\c\"", JsonText.quote("a\"b\\c"), "quote and backslash");
        Check.equal("\"l1\\nl2\\r\\tx\"", JsonText.quote("l1\nl2\r\tx"), "newline, return, tab");
        Check.equal("\"\\u0000\\u001f\\u007f\"", JsonText.quote("\u0000\u001f\u007f"), "other control characters");
        Check.equal("\"\\u2028\\u2029\"", JsonText.quote("\u2028\u2029"), "line and paragraph separators");
        Check.equal("\"\\ud83d\\ude00\"", JsonText.quote("\ud83d\ude00"), "surrogate pair as two escapes");
        Check.equal("\"\\udc00x\"", JsonText.quote("\udc00x"), "a lone surrogate stays valid JSON");
        Check.equal("\"caf\u00e9 /path\"", JsonText.quote("caf\u00e9 /path"), "other characters as they are");
        Check.equal("null", JsonText.quote(null), "null string");
        Check.equal(null, JsonText.orNull(""), "empty string becomes null");
    }

    private static void truncation() {
        Check.equal("abc", JsonText.clip("abc", 3), "a string at the limit is kept");
        Check.equal("abcd...", JsonText.clip("abcdefghij", 7), "a cut keeps max-3 characters and the marker");
        Check.equal(7, JsonText.clip("abcdefghij", 7).length(), "a cut string has exactly max characters");
        Check.equal("ab...", JsonText.clip("ab\ud83d\ude00cdefgh", 6), "a cut never splits a surrogate pair");
        Check.equal(null, JsonText.clip(null, 5), "null stays null");
        char[] longText = new char[1000];
        Arrays.fill(longText, 'x');
        Check.equal(300, JsonText.clip(new String(longText), 300).length(), "the default message bound");
    }

    private static void ordering() {
        JsonText.Obj inner = new JsonText.Obj().put("z", 1).put("a", 2);
        String json = new JsonText.Obj().put("type", "t").put("n", 5L).put("ok", true).put("none", null)
                .put("list", List.of("x", 3, List.of())).put("inner", inner).text();
        Check.equal("{\"type\":\"t\",\"n\":5,\"ok\":true,\"none\":null,\"list\":[\"x\",3,[]],\"inner\":{\"z\":1,\"a\":2}}",
                json, "insertion order, literals, nested arrays and objects");
        JsonText.Obj merged = new JsonText.Obj().put("v", 1).putAll(new JsonText.Obj().put("b", "2")).putAll(new JsonText.Obj());
        Check.equal("{\"v\":1,\"b\":\"2\"}", merged.text(), "putAll appends after existing fields, empty adds nothing");
        Check.equal("{}", new JsonText.Obj().putAll(new JsonText.Obj()).text(), "empty object");
        Check.equal(json, new JsonText.Obj().put("type", "t").put("n", 5L).put("ok", true).put("none", null)
                .put("list", List.of("x", 3, List.of())).put("inner", inner).text(), "the same fields give the same bytes");
    }
}
