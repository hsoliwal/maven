// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and contributors
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.ast;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Standalone AST-only byte-coordinate delta contract: no file image dependency. */
public final class SyntaxDeltaContract {
    private static int checks;
    private static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
        checks++;
    }
    private static void refuse(Class<? extends Throwable> expected, Runnable action) {
        try { action.run(); }
        catch (Throwable actual) {
            if (!expected.isInstance(actual)) throw new AssertionError("wrong rejection", actual);
            checks++; return;
        }
        throw new AssertionError("accepted invalid AST delta");
    }
    private static SyntaxTree expr(String source, String grammar) {
        byte[] b = source.getBytes(StandardCharsets.UTF_8);
        int leftEnd = "α".getBytes(StandardCharsets.UTF_8).length;
        int rightStart = b.length - "β".getBytes(StandardCharsets.UTF_8).length;
        var tree = SyntaxTree.builder(grammar, b);
        tree.open("expr", "", 0, SyntaxTree.NAMED);
        tree.leaf("id", "left", 0, leftEnd, SyntaxTree.NAMED | SyntaxTree.TOKEN);
        tree.leaf("op", "operator", leftEnd + 1, leftEnd + 2, SyntaxTree.TOKEN);
        tree.leaf("id", "right", rightStart, b.length, SyntaxTree.NAMED | SyntaxTree.TOKEN);
        tree.close(b.length);
        return tree.build();
    }
    private record SimpleMap(String oldSha256, String newSha256, int from, int to,
                             int insertedBytes) implements SyntaxDelta.SourceEditMap {
        public boolean touchesOldSpan(int start, int end) {
            if (from == to) return start <= from && from <= end;
            return start < to && from < end;
        }
        public int oldToNew(int p, boolean rightAffinity) {
            if (p < from) return p;
            if (p == from) return rightAffinity ? from + insertedBytes : from;
            if (p < to) return -1;
            return p + insertedBytes - (to - from);
        }
    }
    private static SyntaxDelta.SourceEditMap unchanged(SyntaxTree prior, SyntaxTree next) {
        return new SimpleMap(prior.sourceSha256(), next.sourceSha256(), -1, -1, 0);
    }
    public static void main(String[] args) {
        var before = expr("α + β", "java/expression");
        var after = expr("α - β", "java/expression");
        var change = new SimpleMap(before.sourceSha256(), after.sourceSha256(), 3, 4, 1);
        var matched = SyntaxDelta.match(new SyntaxIndex(before), new SyntaxIndex(after), change);
        check(matched.candidates() == 2 && !matched.rootCandidate(), "unmodified AST leaves survive reparse");
        check(matched.newNodeForOld(1) == 1 && matched.newNodeForOld(3) == 3,
                "native kinds, fields and byte offsets retained");
        check(Arrays.equals(matched.oldUnmatched(), new int[] {0,2})
                && Arrays.equals(matched.newUnmatched(), new int[] {0,2}), "changed root and operator");
        check(matched.oldNodeForNew(1) == 1 && matched.oldNodeForNew(3) == 3,
                "bidirectional, unique occurrence mapping");
        check(!matched.oldProjectionSha256().equals(matched.newProjectionSha256()),
                "source projections remain generation-specific");
        var identity = SyntaxDelta.match(new SyntaxIndex(before), new SyntaxIndex(before),
                unchanged(before, before));
        check(identity.rootCandidate() && identity.candidates() == before.size(), "exact fixed point");
        var prefix = SyntaxTree.builder("java/expression", "##α + β".getBytes(StandardCharsets.UTF_8));
        prefix.open("expr", "", 0, SyntaxTree.NAMED);
        prefix.leaf("id", "left", 2, 4, SyntaxTree.NAMED | SyntaxTree.TOKEN);
        prefix.leaf("op", "operator", 5, 6, SyntaxTree.TOKEN);
        prefix.leaf("id", "right", 7, 9, SyntaxTree.NAMED | SyntaxTree.TOKEN);
        prefix.close(9);
        var shifted = prefix.build();
        var withPrefix = SyntaxDelta.match(new SyntaxIndex(before), new SyntaxIndex(shifted),
                new SimpleMap(before.sourceSha256(), shifted.sourceSha256(), 0, 0, 2));
        check(withPrefix.newNodeForOld(3) == 3 && withPrefix.newNodeForOld(1) == -1,
                "shifted unaffected spans match; insertion boundary remains conservative");
        refuse(IllegalArgumentException.class, () -> SyntaxDelta.match(new SyntaxIndex(before),
                new SyntaxIndex(expr("α - β", "other/parser")), change));
        refuse(IllegalStateException.class, () -> SyntaxDelta.match(new SyntaxIndex(before),
                new SyntaxIndex(after),
                new SimpleMap(after.sourceSha256(), after.sourceSha256(), 3, 4, 1)));
        var ambiguous = SyntaxTree.builder("partial/error", new byte[] {'a'});
        ambiguous.open("file", "", 0, SyntaxTree.NAMED);
        ambiguous.leaf("missing", "expected", 1, 1, SyntaxTree.TOKEN | SyntaxTree.MISSING);
        ambiguous.leaf("missing", "expected", 1, 1, SyntaxTree.TOKEN | SyntaxTree.MISSING);
        ambiguous.close(1);
        var doubled = ambiguous.build();
        var noGuess = SyntaxDelta.match(new SyntaxIndex(doubled), new SyntaxIndex(doubled),
                unchanged(doubled, doubled));
        check(noGuess.candidates() == 1 && noGuess.newNodeForOld(1) == -1
                && noGuess.newNodeForOld(2) == -1, "ambiguous zero-width nodes never arbitrarily matched");
        //FLLDCIM: MRL - merge-loss leaf 71 (origin a94236ef04c9, retain 615c10fe7e9c): duplicate recovery tokens
        // Old source may contain TWO identical zero-width recovery occurrences,
        // while the independently reparsed source contains only ONE. A single
        // available new node is still ambiguous and must not steal the first old.
        var oneBuilder = SyntaxTree.builder("partial/error", new byte[] {'a'});
        oneBuilder.open("file", "", 0, SyntaxTree.NAMED);
        oneBuilder.leaf("missing", "expected", 1, 1, SyntaxTree.TOKEN | SyntaxTree.MISSING);
        oneBuilder.close(1);
        var one = oneBuilder.build();
        var oldDuplicate = SyntaxDelta.match(new SyntaxIndex(doubled),
                new SyntaxIndex(one), unchanged(doubled, one));
        check(oldDuplicate.candidates() == 0
                && oldDuplicate.newNodeForOld(1) == -1
                && oldDuplicate.newNodeForOld(2) == -1
                && oldDuplicate.oldNodeForNew(1) == -1,
                "two equivalent old occurrences cannot claim one new recovery token");
        var newDuplicate = SyntaxDelta.match(new SyntaxIndex(one),
                new SyntaxIndex(doubled), unchanged(one, doubled));
        check(newDuplicate.candidates() == 0
                && newDuplicate.newNodeForOld(1) == -1,
                "one old recovery token cannot claim two equivalent new occurrences");
        var unique = SyntaxDelta.match(new SyntaxIndex(one),
                new SyntaxIndex(one), unchanged(one, one));
        check(unique.candidates() == one.size() && unique.newNodeForOld(1) == 1,
                "unique recovery token still matches bidirectionally");
        var tripleBuilder = SyntaxTree.builder("partial/error", new byte[] {'a'});
        tripleBuilder.open("file", "", 0, SyntaxTree.NAMED);
        for (int i = 0; i < 3; i++)
            tripleBuilder.leaf("missing", "expected", 1, 1, SyntaxTree.TOKEN | SyntaxTree.MISSING);
        tripleBuilder.close(1);
        var triple = tripleBuilder.build();
        var threeToOne = SyntaxDelta.match(new SyntaxIndex(triple),
                new SyntaxIndex(one), unchanged(triple, one));
        check(threeToOne.candidates() == 0
                && threeToOne.oldNodeForNew(1) == -1
                && Arrays.equals(threeToOne.oldUnmatched(), new int[] {0, 1, 2, 3}),
                "third indistinguishable old token cannot reinstate an invalidated match");
        var longBuilder = SyntaxTree.builder("large/js", new byte[1200]);
        longBuilder.open("file", "", 0, 0);
        for (int i = 0; i < 1200; i++)
            longBuilder.leaf("token", "", i, i + 1, SyntaxTree.TOKEN);
        longBuilder.close(1200);
        var large = longBuilder.build();
        check(SyntaxDelta.match(new SyntaxIndex(large), new SyntaxIndex(large),
                unchanged(large, large)).candidates() == large.size(), "1200-node primitive join");
        System.out.println("SYNTAX_DELTA_PASS checks=" + checks);
    }
}
