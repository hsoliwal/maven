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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.openrewrite.Tree;
import org.openrewrite.marker.Markers;
import org.openrewrite.yaml.tree.Yaml;

/**
 * Inserts a scalar entry into a YAML sequence, in flow ({@code [17, 25]}) or block ({@code - 17}) form, keeping the
 * layout and the comments of the other entries. The new scalar is a copy of a given entry's scalar (its quoting style,
 * tag and spacing, without its anchor) with a new text. In rewrite-yaml the same-line comment of a block entry is in
 * the prefix of the node after it, and that of a flow entry in the block prefix of the next entry (or in the closing
 * bracket prefix after the last entry); the new entry takes the line such a comment is on, so every comment stays on
 * the line of the entry it was written for. When a block sequence gets a new last entry, the comment of the old last
 * entry is in the prefix of the node after the sequence: the result then names the new entry, which takes the first
 * line of that prefix.
 */
final class SequenceInsert {
    /** The sequence with the new entry, and the new entry when it takes the first line of the next node's prefix. */
    record Result(Yaml.Sequence sequence, Optional<UUID> takesNextFirstLine) {}

    private SequenceInsert() {}

    /** Inserts at the index; empty when a block sequence has no line break to copy (entries on one line). */
    static Optional<Result> insert(Yaml.Sequence sequence, int index, Yaml.Scalar copied, String text) {
        Yaml.Scalar scalar =
                copied.withId(Tree.randomId()).withMarkers(Markers.EMPTY).withAnchor(null).withValue(text);
        return sequence.getOpeningBracketPrefix() == null
                ? block(sequence, index, scalar)
                : Optional.of(new Result(flow(sequence, index, scalar), Optional.empty()));
    }

    private static Optional<Result> block(Yaml.Sequence sequence, int index, Yaml.Scalar scalar) {
        List<Yaml.Sequence.Entry> entries = new ArrayList<>(sequence.getEntries());
        boolean last = index == entries.size();
        Yaml.Sequence.Entry next = entries.get(last ? index - 1 : index);
        if (next.getPrefix().indexOf('\n') < 0) {
            return Optional.empty();
        }
        Yaml.Sequence.Entry added = entry(scalar, true, null).withPrefix(fromLineBreak(next.getPrefix()));
        if (last) {
            entries.add(added);
            return Optional.of(new Result(sequence.withEntries(entries), Optional.of(added.getId())));
        }
        entries.set(index, next.withPrefix(fromLineBreak(next.getPrefix())));
        entries.add(index, added.withPrefix(next.getPrefix()));
        return Optional.of(new Result(sequence.withEntries(entries), Optional.empty()));
    }

    private static Yaml.Sequence flow(Yaml.Sequence sequence, int index, Yaml.Scalar scalar) {
        List<Yaml.Sequence.Entry> entries = new ArrayList<>(sequence.getEntries());
        String comma = Optional.ofNullable(entries.get(0).getTrailingCommaPrefix()).orElse("");
        if (index == entries.size()) {
            return flowLast(sequence, entries, scalar, comma);
        }
        Yaml.Sequence.Entry next = entries.get(index);
        String before = next.getBlock().getPrefix();
        String after = index == 0 ? separator(entries) : fromLineBreak(before);
        entries.set(index, next.withBlock(next.getBlock().withPrefix(after)));
        entries.add(index, entry(scalar.withPrefix(before), false, comma));
        return sequence.withEntries(entries);
    }

    /** The new last entry of a flow sequence takes the old last entry's trailing comma, if any. */
    private static Yaml.Sequence flowLast(
            Yaml.Sequence sequence, List<Yaml.Sequence.Entry> entries, Yaml.Scalar scalar, String comma) {
        Yaml.Sequence.Entry previous = entries.get(entries.size() - 1);
        String closing = sequence.getClosingBracketPrefix();
        String trailingComma = previous.getTrailingCommaPrefix();
        String previousComma = trailingComma == null ? comma : trailingComma;
        entries.set(entries.size() - 1, previous.withTrailingCommaPrefix(previousComma));
        String before = commentLine(closing) + separator(entries);
        entries.add(entry(scalar.withPrefix(before), false, trailingComma));
        return sequence.withEntries(entries).withClosingBracketPrefix(fromLineBreak(closing));
    }

    /** The text a flow sequence puts after its commas: that before its second entry, else one space. */
    private static String separator(List<Yaml.Sequence.Entry> entries) {
        String first = entries.get(0).getBlock().getPrefix();
        if (entries.size() > 1) {
            return fromLineBreak(entries.get(1).getBlock().getPrefix());
        }
        return first.indexOf('\n') < 0 ? " " : fromLineBreak(first);
    }

    private static Yaml.Sequence.Entry entry(Yaml.Scalar scalar, boolean dash, String trailingCommaPrefix) {
        return new Yaml.Sequence.Entry(Tree.randomId(), "", Markers.EMPTY, scalar, dash, trailingCommaPrefix);
    }

    /** The text before the first line break (a same-line comment), or nothing when there is no line break. */
    private static String commentLine(String prefix) {
        int lineBreak = prefix.indexOf('\n');
        return lineBreak < 0 ? "" : prefix.substring(0, lineBreak);
    }

    /** The text from the first line break on, or all of it when there is no line break. */
    private static String fromLineBreak(String prefix) {
        int lineBreak = prefix.indexOf('\n');
        return lineBreak < 0 ? prefix : prefix.substring(lineBreak);
    }
}
