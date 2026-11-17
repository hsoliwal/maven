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
package org.apache.maven.recipes.javac.boot;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The boot entry's own options ({@code impl=}, {@code minHeap=}, {@code headroom=}) and the rest, which go to the
 * implementation unchanged. javac splits the option text on runs of whitespace; a piece that starts no
 * {@code key=} continues the value before it, so an {@code impl=} path with single spaces arrives whole.
 */
final class BootOptions {
    static final int DEFAULT_MIN_HEAP_MB = 256;
    static final int DEFAULT_HEADROOM_PERCENT = 30;
    private static final String IMPL = "impl";
    private static final String MIN_HEAP = "minHeap";
    private static final String HEADROOM = "headroom";
    private static final Pattern OWN = Pattern.compile("^(" + IMPL + "|" + MIN_HEAP + "|" + HEADROOM + ")=.*");
    private static final Pattern ANY = Pattern.compile("^[A-Za-z][A-Za-z0-9]*=.*");
    private static final String LIST_FILE = "@";
    private static final String SEPARATOR = "\\|";

    private final Map<String, String> own;
    private final List<String> rest;

    private BootOptions(Map<String, String> own, List<String> rest) {
        this.own = own;
        this.rest = rest;
    }

    /** Splits javac's pieces into the boot options and the implementation's. */
    static BootOptions parse(String... args) {
        BootOptions options = new BootOptions(new HashMap<String, String>(), new ArrayList<String>());
        String owner = "";
        for (String arg : args) {
            owner = options.take(owner, arg);
        }
        return options;
    }

    /** Files one piece; returns the boot option it belongs to, or the empty string for the implementation's. */
    private String take(String owner, String arg) {
        if (OWN.matcher(arg).matches()) {
            String key = arg.substring(0, arg.indexOf('='));
            own.put(key, arg.substring(key.length() + 1));
            return key;
        }
        if (owner.isEmpty() || ANY.matcher(arg).matches()) {
            rest.add(arg);
            return "";
        }
        own.put(owner, own.get(owner) + " " + arg);
        return owner;
    }

    int minHeapMb() {
        return number(MIN_HEAP, DEFAULT_MIN_HEAP_MB);
    }

    int headroomPercent() {
        return number(HEADROOM, DEFAULT_HEADROOM_PERCENT);
    }

    private int number(String key, int fallback) {
        String value = own.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + "='" + value + "' is no number", e);
        }
    }

    /** The options for the implementation, as javac passed them. */
    String[] rest() {
        return rest.toArray(new String[0]);
    }

    /** The implementation's class path; empty when {@code impl=} is absent. */
    URL[] implementation() throws IOException {
        String value = own.get(IMPL);
        if (value == null || value.trim().isEmpty()) {
            return new URL[0];
        }
        List<URL> urls = new ArrayList<URL>();
        for (String entry : entries(value.trim())) {
            if (!entry.trim().isEmpty()) {
                urls.add(url(entry.trim()));
            }
        }
        return urls.toArray(new URL[0]);
    }

    private static List<String> entries(String value) throws IOException {
        if (value.startsWith(LIST_FILE)) {
            return Files.readAllLines(Paths.get(value.substring(LIST_FILE.length())), StandardCharsets.UTF_8);
        }
        List<String> entries = new ArrayList<String>();
        for (String entry : value.split(SEPARATOR)) {
            entries.add(entry);
        }
        return entries;
    }

    private static URL url(String entry) throws IOException {
        try {
            return entry.startsWith("file:")
                    ? new URI(entry).toURL()
                    : new File(entry).toURI().toURL();
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new IOException(IMPL + "= entry '" + entry + "' is no path or file: URI", e);
        }
    }
}
