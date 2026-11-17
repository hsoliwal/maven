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
package org.apache.maven.impl.process;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringTokenizer;

import org.apache.maven.api.services.ProcessLauncherException;

/**
 * Splits a command line into arguments.
 * <p>
 * Adapted from maven-shared-utils 3.5.0 {@code CommandLineUtils.translateCommandline} (Apache-2.0), whose state
 * machine surefire copies for {@code argLine}: spaces separate arguments, single and double quotes group them and
 * are dropped, a backslash is kept and makes the next quote literal. The loop over tokens is split into one method
 * per state.
 */
final class CommandLineTokenizer {

    private static final String SINGLE_QUOTE = "'";
    private static final String DOUBLE_QUOTE = "\"";
    private static final String BACKSLASH = "\\";

    private enum State {
        NORMAL,
        IN_QUOTE,
        IN_DOUBLE_QUOTE
    }

    private final List<String> tokens = new ArrayList<>();
    private final StringBuilder current = new StringBuilder();
    private State state = State.NORMAL;
    private boolean inEscape;

    private CommandLineTokenizer() {}

    /**
     * Splits a command line.
     *
     * @param commandLine the command line, may be null
     * @return the arguments
     * @throws ProcessLauncherException on unbalanced quotes
     */
    static List<String> tokenize(String commandLine) {
        if (commandLine == null || commandLine.isEmpty()) {
            return List.of();
        }
        CommandLineTokenizer tokenizer = new CommandLineTokenizer();
        Collections.list(new StringTokenizer(commandLine, "\"' \\", true))
                .forEach(token -> tokenizer.accept((String) token));
        return tokenizer.finish(commandLine);
    }

    private void accept(String token) {
        switch (state) {
            case IN_QUOTE -> quoted(SINGLE_QUOTE, token);
            case IN_DOUBLE_QUOTE -> quoted(DOUBLE_QUOTE, token);
            default -> normal(token);
        }
    }

    private void quoted(String quote, String token) {
        if (quote.equals(token) && !inEscape) {
            state = State.NORMAL;
            return;
        }
        current.append(token);
        inEscape = !quote.equals(token) && BACKSLASH.equals(token);
    }

    private void normal(String token) {
        if (SINGLE_QUOTE.equals(token) || DOUBLE_QUOTE.equals(token)) {
            openQuote(token);
        } else if (" ".equals(token)) {
            split();
        } else {
            current.append(token);
            inEscape = BACKSLASH.equals(token);
        }
    }

    private void openQuote(String quote) {
        if (inEscape) {
            inEscape = false;
            current.append(quote);
        } else {
            state = SINGLE_QUOTE.equals(quote) ? State.IN_QUOTE : State.IN_DOUBLE_QUOTE;
        }
    }

    private void split() {
        if (current.length() != 0) {
            tokens.add(current.toString());
            current.setLength(0);
        }
    }

    private List<String> finish(String commandLine) {
        split();
        if (state != State.NORMAL) {
            throw new ProcessLauncherException("unbalanced quotes in " + commandLine);
        }
        return List.copyOf(tokens);
    }
}
