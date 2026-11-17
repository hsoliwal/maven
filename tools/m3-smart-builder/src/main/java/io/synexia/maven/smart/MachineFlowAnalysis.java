// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ContinueTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;

/**
 * Bounded, compiler-symbol-guided *may* reaching-definition analysis of a structured Java AST.
 * This is not javac's full CFG: implicit exception paths, heap aliases, interprocedural effects,
 * switch/finally and expression-level branches remain explicit, non-promotable residue.
 */
final class MachineFlowAnalysis {
    static final int INCOMPLETE_EXCEPTION = 0x001;
    static final int INCOMPLETE_SWITCH_TRY = 0x002;
    static final int INCOMPLETE_NESTED = 0x004;
    static final int INCOMPLETE_EXPRESSION = 0x008;
    static final int INCOMPLETE_MONITOR = 0x010;
    static final int INCOMPLETE_HEAP = 0x020;
    static final int INCOMPLETE_UNBOUND = 0x040;
    static final int INCOMPLETE_CONTROL = 0x080;
    static final int ALL_INCOMPLETE = 0x0ff;
    static final int MAX_NODES = 256;
    static final int MAX_EDGES = 1024;
    static final int MAX_LINKS = 8192;

    record Facts(int nodes, int edges, String cfg, int reads, int writes, int links,
                 String defUse, int incomplete) {
        String columns() {
            return nodes + "\t" + edges + "\t" + cfg + "\t" + reads + "\t" + writes
                    + "\t" + links + "\t" + defUse + "\t"
                    + String.format(Locale.ROOT, "%04x", incomplete);
        }
    }

    private record Edge(int from, int to, String kind) { }
    private record Access(int symbol, boolean write) { }
    private record Definition(int node, int symbol) { }
    private record Context(int breakTarget, int continueTarget) { }

    private static final class Node {
        final int id;
        final String kind;
        final Tree expression;
        final List<Access> accesses = new ArrayList<>();

        Node(int id, String kind, Tree expression) {
            this.id = id;
            this.kind = kind;
            this.expression = expression;
        }
    }

    private MachineFlowAnalysis() { }

    static Facts inspect(Trees trees, TreePath atom) throws IOException {
        Tree executable = switch (atom.getLeaf()) {
            case MethodTree method -> method.getBody();
            case LambdaExpressionTree lambda -> lambda.getBody();
            case BlockTree block -> block;
            default -> null;
        };
        Graph graph = new Graph();
        int exit = graph.node("EXIT", null);
        int first = executable == null ? exit : graph.emit(executable, exit, new Context(-1, -1));
        int entry = graph.node("ENTRY", null);
        graph.edge(entry, first, "ENTER");
        TreePath executablePath = executable == null ? null : TreePath.getPath(atom, executable);
        if (executable != null && executablePath == null)
            throw new IOException("executable compiler path unavailable");
        graph.symbols(trees, atom, executablePath, entry);
        return graph.finish();
    }

    private static final class Graph {
        final List<Node> nodes = new ArrayList<>();
        final List<Edge> edges = new ArrayList<>();
        int incomplete;

        int node(String kind, Tree access) throws IOException {
            if (nodes.size() >= MAX_NODES) throw new IOException("CFG node budget exceeded");
            int id = nodes.size();
            nodes.add(new Node(id, kind, access));
            return id;
        }

        void edge(int from, int to, String kind) throws IOException {
            if (edges.size() >= MAX_EDGES) throw new IOException("CFG edge budget exceeded");
            edges.add(new Edge(from, to, kind));
        }

        int emit(Tree tree, int next, Context context) throws IOException {
            if (tree instanceof BlockTree block) return sequence(block.getStatements(), next, context);
            if (tree instanceof IfTree branch) {
                int condition = node("IF", branch.getCondition());
                int trueEntry = emit(branch.getThenStatement(), next, context);
                int falseEntry = branch.getElseStatement() == null ? next
                        : emit(branch.getElseStatement(), next, context);
                edge(condition, trueEntry, "TRUE");
                edge(condition, falseEntry, "FALSE");
                return condition;
            }
            if (tree instanceof WhileLoopTree loop) {
                int condition = node("WHILE", loop.getCondition());
                int body = emit(loop.getStatement(), condition, new Context(next, condition));
                edge(condition, body, "TRUE");
                edge(condition, next, "FALSE");
                return condition;
            }
            if (tree instanceof DoWhileLoopTree loop) {
                int condition = node("DO_WHILE", loop.getCondition());
                int body = emit(loop.getStatement(), condition, new Context(next, condition));
                edge(condition, body, "TRUE");
                edge(condition, next, "FALSE");
                return body;
            }
            if (tree instanceof ForLoopTree loop) {
                int condition = node("FOR", loop.getCondition());
                int update = sequence(loop.getUpdate(), condition, context);
                int body = emit(loop.getStatement(), update, new Context(next, update));
                edge(condition, body, "TRUE");
                if (loop.getCondition() != null) edge(condition, next, "FALSE");
                return sequence(loop.getInitializer(), condition, context);
            }
            if (tree instanceof EnhancedForLoopTree loop) {
                incomplete |= INCOMPLETE_EXPRESSION; // Iterator acquisition and side effects are opaque.
                int condition = node("ENHANCED_FOR", loop.getExpression());
                int body = emit(loop.getStatement(), condition, new Context(next, condition));
                edge(condition, body, "ITERATE");
                edge(condition, next, "DONE");
                return condition;
            }
            if (tree instanceof SynchronizedTree monitor) {
                incomplete |= INCOMPLETE_MONITOR | INCOMPLETE_EXCEPTION;
                int enter = node("MONITOR_ENTER", monitor.getExpression());
                int body = emit(monitor.getBlock(), next, context);
                edge(enter, body, "BODY");
                return enter;
            }
            int current = node(tree.getKind().name(), tree);
            if (tree instanceof ReturnTree) {
                edge(current, 0, "RETURN");
            } else if (tree instanceof ThrowTree) {
                incomplete |= INCOMPLETE_EXCEPTION;
                edge(current, 0, "THROW_UNMODELED");
            } else if (tree instanceof BreakTree jump) {
                if (jump.getLabel() != null || context.breakTarget() < 0) incomplete |= INCOMPLETE_CONTROL;
                edge(current, context.breakTarget() < 0 || jump.getLabel() != null ? 0 : context.breakTarget(), "BREAK");
            } else if (tree instanceof ContinueTree jump) {
                if (jump.getLabel() != null || context.continueTarget() < 0) incomplete |= INCOMPLETE_CONTROL;
                edge(current, context.continueTarget() < 0 || jump.getLabel() != null ? 0
                        : context.continueTarget(), "CONTINUE");
            } else {
                if (tree instanceof StatementTree statement) {
                    switch (statement.getKind()) {
                        case TRY, SWITCH -> incomplete |= INCOMPLETE_SWITCH_TRY | INCOMPLETE_EXCEPTION;
                        case LABELED_STATEMENT, YIELD -> incomplete |= INCOMPLETE_CONTROL;
                        case CLASS -> incomplete |= INCOMPLETE_NESTED;
                        default -> { }
                    }
                }
                edge(current, next, "NEXT");
            }
            return current;
        }

        int sequence(List<? extends Tree> statements, int next, Context context) throws IOException {
            int first = next;
            for (int i = statements.size() - 1; i >= 0; i--) first = emit(statements.get(i), first, context);
            return first;
        }

        void symbols(Trees trees, TreePath atom, TreePath body, int entry) {
            AccessScanner scanner = new AccessScanner(trees, this);
            // Parameters are reaching definitions at the atom entry, not executable write sites.
            List<? extends VariableTree> parameters = switch (atom.getLeaf()) {
                case MethodTree method -> method.getParameters();
                case LambdaExpressionTree lambda -> lambda.getParameters();
                default -> List.of();
            };
            for (VariableTree parameter : parameters) {
                TreePath parameterPath = TreePath.getPath(atom, parameter);
                if (parameterPath != null) scanner.parameter(parameterPath, nodes.get(entry));
            }
            if (body != null) {
                // Cues about unmodelled evaluation paths are explicit and never inferred as CFG edges.
                new TreeScanner<Void, Void>() {
                    @Override public Void scan(Tree tree, Void ignored) {
                        if (tree == null) return null;
                        switch (tree.getKind()) {
                            case CONDITIONAL_EXPRESSION, CONDITIONAL_AND, CONDITIONAL_OR,
                                 SWITCH_EXPRESSION -> incomplete |= INCOMPLETE_EXPRESSION;
                            case TRY, SWITCH -> incomplete |= INCOMPLETE_SWITCH_TRY | INCOMPLETE_EXCEPTION;
                            case LAMBDA_EXPRESSION, CLASS -> incomplete |= INCOMPLETE_NESTED;
                            case METHOD_INVOCATION, NEW_CLASS -> incomplete |= INCOMPLETE_EXCEPTION;
                            default -> { }
                        }
                        return super.scan(tree, ignored);
                    }
                }.scan(body.getLeaf(), null);
                for (Node node : nodes) if (node.expression != null) {
                    TreePath path = TreePath.getPath(body, node.expression);
                    if (path == null) {
                        incomplete |= INCOMPLETE_CONTROL;
                        continue;
                    }
                    scanner.scan(path, node);
                }
            }
        }

        Facts finish() throws IOException {
            List<String> shape = new ArrayList<>();
            for (Node node : nodes) shape.add("N:" + node.id + ":" + node.kind);
            edges.stream().sorted(Comparator.comparingInt(Edge::from).thenComparingInt(Edge::to)
                    .thenComparing(Edge::kind))
                    .forEach(e -> shape.add("E:" + e.from + ":" + e.to + ":" + e.kind));
            List<List<Integer>> predecessors = new ArrayList<>();
            for (int i = 0; i < nodes.size(); i++) predecessors.add(new ArrayList<>());
            for (Edge edge : edges) predecessors.get(edge.to).add(edge.from);
            List<Set<Definition>> out = new ArrayList<>();
            for (int i = 0; i < nodes.size(); i++) out.add(Set.of());
            List<Set<Definition>> in = new ArrayList<>(out);
            boolean changed = true;
            for (int iteration = 0; changed && iteration <= nodes.size() * 2; iteration++) {
                changed = false;
                for (Node node : nodes) {
                    Set<Definition> entering = new HashSet<>();
                    for (int predecessor : predecessors.get(node.id)) entering.addAll(out.get(predecessor));
                    Set<Integer> written = new HashSet<>();
                    for (Access access : node.accesses) if (access.write) written.add(access.symbol);
                    Set<Definition> leaving = new HashSet<>();
                    for (Definition def : entering) if (!written.contains(def.symbol)) leaving.add(def);
                    for (int symbol : written) leaving.add(new Definition(node.id, symbol));
                    if (!in.get(node.id).equals(entering) || !out.get(node.id).equals(leaving)) {
                        changed = true;
                        in.set(node.id, Set.copyOf(entering));
                        out.set(node.id, Set.copyOf(leaving));
                    }
                }
                if (iteration == nodes.size() * 2 && changed)
                    throw new IOException("CFG reaching-definition fixed point budget exceeded");
            }
            List<String> links = new ArrayList<>();
            int reads = 0, writes = 0;
            for (Node node : nodes) {
                int ordinal = 0;
                for (Access access : node.accesses) {
                    if (access.write) {
                        if (!"ENTRY".equals(node.kind)) writes++;
                        continue;
                    }
                    reads++;
                    int matches = 0;
                    for (Definition definition : in.get(node.id)) if (definition.symbol == access.symbol) {
                        links.add(node.id + ":" + ordinal + ":" + access.symbol + "<-" + definition.node);
                        matches++;
                    }
                    if (matches == 0) incomplete |= INCOMPLETE_UNBOUND;
                    if (links.size() > MAX_LINKS) throw new IOException("CFG reaching-definition link budget exceeded");
                    ordinal++;
                }
            }
            links.sort(String::compareTo);
            return new Facts(nodes.size(), edges.size(), hash(shape), reads, writes,
                    links.size(), hash(links), incomplete);
        }
    }

    private static final class AccessScanner extends TreePathScanner<Void, Node> {
        private final Trees trees;
        private final Graph graph;
        private final IdentityHashMap<Element, Integer> symbols = new IdentityHashMap<>();

        AccessScanner(Trees trees, Graph graph) { this.trees = trees; this.graph = graph; }

        void parameter(TreePath path, Node entry) { record(path, true, entry); }

        private void record(TreePath path, boolean write, Node node) {
            Element symbol = trees.getElement(path);
            if (symbol == null) return;
            ElementKind kind = symbol.getKind();
            if (kind == ElementKind.FIELD || kind == ElementKind.ENUM_CONSTANT) {
                graph.incomplete |= INCOMPLETE_HEAP;
                return;
            }
            if (kind != ElementKind.LOCAL_VARIABLE && kind != ElementKind.PARAMETER
                    && kind != ElementKind.EXCEPTION_PARAMETER && kind != ElementKind.RESOURCE_VARIABLE
                    && kind != ElementKind.BINDING_VARIABLE) return;
            int id = symbols.computeIfAbsent(symbol, ignored -> symbols.size());
            node.accesses.add(new Access(id, write));
        }

        @Override public Void visitVariable(VariableTree variable, Node node) {
            if (variable.getInitializer() != null) {
                scan(variable.getInitializer(), node);
                record(getCurrentPath(), true, node);
            }
            return null;
        }

        @Override public Void visitIdentifier(IdentifierTree identifier, Node node) {
            record(getCurrentPath(), false, node);
            return null;
        }

        @Override public Void visitMemberSelect(MemberSelectTree select, Node node) {
            record(getCurrentPath(), false, node);
            scan(select.getExpression(), node);
            return null;
        }

        private void target(Tree target, boolean alsoRead, Node node) {
            TreePath targetPath = new TreePath(getCurrentPath(), target);
            if (target instanceof ArrayAccessTree array) {
                graph.incomplete |= INCOMPLETE_HEAP;
                scan(array.getExpression(), node);
                scan(array.getIndex(), node);
            } else if (target instanceof IdentifierTree || target instanceof MemberSelectTree) {
                if (alsoRead) record(targetPath, false, node);
                record(targetPath, true, node);
                if (target instanceof MemberSelectTree select) scan(select.getExpression(), node);
            } else {
                graph.incomplete |= INCOMPLETE_HEAP;
                scan(target, node);
            }
        }

        @Override public Void visitAssignment(AssignmentTree assign, Node node) {
            scan(assign.getExpression(), node);
            target(assign.getVariable(), false, node);
            return null;
        }

        @Override public Void visitCompoundAssignment(CompoundAssignmentTree assign, Node node) {
            scan(assign.getExpression(), node);
            target(assign.getVariable(), true, node);
            return null;
        }

        @Override public Void visitUnary(UnaryTree unary, Node node) {
            switch (unary.getKind()) {
                case PREFIX_INCREMENT, POSTFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_DECREMENT ->
                        target(unary.getExpression(), true, node);
                default -> scan(unary.getExpression(), node);
            }
            return null;
        }

        @Override public Void visitLambdaExpression(LambdaExpressionTree lambda, Node node) {
            graph.incomplete |= INCOMPLETE_NESTED;
            return null; // The nested lambda has its own canonical atom.
        }

        @Override public Void visitClass(com.sun.source.tree.ClassTree type, Node node) {
            graph.incomplete |= INCOMPLETE_NESTED;
            return null;
        }
    }

    private static String hash(List<String> tokens) {
        return Hashes.sha256(String.join("\n", tokens).getBytes(StandardCharsets.UTF_8));
    }
}
