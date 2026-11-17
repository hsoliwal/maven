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
package org.apache.maven.recipes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.openrewrite.yaml.tree.Yaml;

/**
 * Drops the raised entries of a YAML sequence whose key a kept entry already has, keeping the layout of the rest.
 * Unchanged entries are always kept; a raised entry is kept when it is the first with its key. The first kept entry
 * takes the sequence's leading spacing, the last one its trailing comma. Between two block entries, the prefix of the
 * lower one holds the same-line comment of the upper one, then the line break and indentation; so a kept entry after
 * dropped ones takes the first line of the first dropped entry's prefix and keeps its own text from the line break
 * on, and each dropped entry's comment goes with it. When the last entries of a block sequence are dropped, their
 * comment is in the prefix of the node after the sequence, so the result carries the first line that prefix needs.
 */
final class RaisedDuplicates {
    /** The remaining sequence, and the first line for the prefix of the node after it (dropped last block entries). */
    record Result(Yaml.Sequence sequence, Optional<String> nextFirstLine) {}

    private RaisedDuplicates() {}

    static Result unchanged(Yaml.Sequence sequence) {
        return new Result(sequence, Optional.empty());
    }

    static Result drop(
            Yaml.Sequence sequence, List<Yaml.Sequence.Entry> raised, Function<Yaml.Sequence.Entry, String> key) {
        List<Yaml.Sequence.Entry> entries = sequence.getEntries();
        if (IntStream.range(0, entries.size()).allMatch(i -> entries.get(i) == raised.get(i))) {
            return unchanged(sequence);
        }
        List<Integer> kept = keptIndexes(entries, raised, key);
        return new Result(sequence.withEntries(layout(entries, raised, kept)), nextFirstLine(sequence, kept));
    }

    /** The prefix with its first line, the text before its first line break, replaced. */
    static String withFirstLine(String prefix, String firstLine) {
        int lineBreak = prefix.indexOf('\n');
        return firstLine + (lineBreak < 0 ? "" : prefix.substring(lineBreak));
    }

    private static List<Integer> keptIndexes(
            List<Yaml.Sequence.Entry> entries,
            List<Yaml.Sequence.Entry> raised,
            Function<Yaml.Sequence.Entry, String> key) {
        Set<String> keys = IntStream.range(0, entries.size())
                .filter(i -> entries.get(i) == raised.get(i))
                .mapToObj(i -> key.apply(entries.get(i)))
                .collect(Collectors.toCollection(HashSet::new));
        return IntStream.range(0, entries.size())
                .filter(i -> entries.get(i) == raised.get(i) || keys.add(key.apply(raised.get(i))))
                .boxed()
                .toList();
    }

    private static List<Yaml.Sequence.Entry> layout(
            List<Yaml.Sequence.Entry> entries, List<Yaml.Sequence.Entry> raised, List<Integer> kept) {
        List<Yaml.Sequence.Entry> laidOut = IntStream.range(0, kept.size())
                .mapToObj(k -> withLinePrefix(raised, kept, k))
                .collect(Collectors.toCollection(ArrayList::new));
        Yaml.Sequence.Entry first = laidOut.get(0);
        laidOut.set(0, first.withBlock(first.getBlock().withPrefix(entries.get(0).getBlock().getPrefix())));
        int end = laidOut.size() - 1;
        String trailingComma = entries.get(entries.size() - 1).getTrailingCommaPrefix();
        laidOut.set(end, laidOut.get(end).withTrailingCommaPrefix(trailingComma));
        return laidOut;
    }

    private static Yaml.Sequence.Entry withLinePrefix(List<Yaml.Sequence.Entry> raised, List<Integer> kept, int k) {
        int index = kept.get(k);
        int firstDropped = k == 0 ? 0 : kept.get(k - 1) + 1;
        Yaml.Sequence.Entry entry = raised.get(index);
        return firstDropped == index
                ? entry
                : entry.withPrefix(withFirstLine(entry.getPrefix(), firstLine(raised.get(firstDropped).getPrefix())));
    }

    private static Optional<String> nextFirstLine(Yaml.Sequence sequence, List<Integer> kept) {
        List<Yaml.Sequence.Entry> entries = sequence.getEntries();
        int next = kept.get(kept.size() - 1) + 1;
        return sequence.getOpeningBracketPrefix() == null && next < entries.size()
                ? Optional.of(firstLine(entries.get(next).getPrefix()))
                : Optional.empty();
    }

    private static String firstLine(String prefix) {
        int lineBreak = prefix.indexOf('\n');
        return lineBreak < 0 ? prefix : prefix.substring(0, lineBreak);
    }
}
