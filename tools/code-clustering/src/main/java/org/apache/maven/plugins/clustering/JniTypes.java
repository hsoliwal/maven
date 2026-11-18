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
package org.apache.maven.plugins.clustering;

import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.lang.model.type.TypeKind;

/**
 * The binary class name and the argument descriptor of a native method, from the syntax tree alone (no class path).
 * Primitive and array types are exact; a type variable is the erasure of its first bound. A reference type by simple
 * name resolves as the compiler resolves it, in this order: a member type of an enclosing class, a single-type
 * import, a type of the same package, a type of an on-demand import, a type of {@code java.lang}. A qualified name
 * whose first part is such a type names its member types. Types are known from the compilation units of the same
 * scan and from the platform classes of the running JDK. The member types of an enclosing class include those it
 * inherits ({@link JniMemberTypes}); when one of its supertypes is not known, the name may be such a member type, so
 * it is {@link JniNames#UNKNOWN} (a qualified name whose first part starts in lower case is then taken as a package).
 * An imported or fully qualified name that neither knows is taken as a top-level class when every part before the
 * last starts in lower case (the package naming convention). Anything else is {@link JniNames#UNKNOWN}, never a guess.
 */
final class JniTypes {
    private static final String OBJECT = "Ljava/lang/Object;";
    private final Map<String, String> binaries = new HashMap<>();
    private final Map<String, Optional<Class<?>>> platform = new HashMap<>();
    private final JniMemberTypes members = new JniMemberTypes(
            (tree, d) -> reference(tree, new Scope(d.unit(), packageOf(d.unit()), d.outers(), "", Map.of())),
            name -> platform.computeIfAbsent(name, JniTypes::load));

    /** Where a native method is: its compilation unit, package, enclosing classes and the type variables in scope. */
    private record Scope(CompilationUnitTree unit, String pkg, List<String> classes, String binary,
            Map<String, List<? extends Tree>> variables) {
        Scope without(String variable) {
            Map<String, List<? extends Tree>> fewer = new HashMap<>(variables);
            fewer.remove(variable);
            return new Scope(unit, pkg, classes, binary, fewer);
        }
    }

    /** Learns the top-level and member types the units declare, by canonical name. */
    JniTypes(List<? extends CompilationUnitTree> units) {
        units.forEach(unit -> unit.getTypeDecls().stream().filter(ClassTree.class::isInstance).map(ClassTree.class::cast)
                .forEach(type -> declare(unit, packageOf(unit), prefix(packageOf(unit)), type, List.of())));
    }

    /** {@code outers}: the canonical names of the classes around {@code type}, innermost first. */
    private void declare(CompilationUnitTree unit, String outer, String binaryOuter, ClassTree type, List<String> outers) {
        String canonical = qualify(outer, type.getSimpleName().toString());
        String binary = binaryOuter + type.getSimpleName();
        binaries.put(canonical, binary);
        members.declare(binary, new JniMemberTypes.Declaration(type, unit, outers));
        List<String> inner = Stream.concat(Stream.of(canonical), outers.stream()).toList();
        type.getMembers().stream().filter(ClassTree.class::isInstance).map(ClassTree.class::cast)
                .forEach(member -> declare(unit, canonical, binary + "$", member, inner));
    }

    /** The binary names in internal form of the classes the units declare. */
    Collection<String> binaries() {
        return Collections.unmodifiableCollection(binaries.values());
    }

    /**
     * The native method whose path is {@code at}; empty in a local or anonymous class, whose binary name the source
     * alone does not fix.
     */
    Optional<JniMethod> method(String path, String symbol, TreePath at) {
        MethodTree method = (MethodTree) at.getLeaf();
        return enclosing(at.getParentPath()).filter(classes -> !classes.isEmpty()).map(classes -> {
            Scope scope = scope(at.getCompilationUnit(), classes, method);
            String arguments = method.getParameters().stream().map(p -> descriptor(p.getType(), scope))
                    .collect(Collectors.joining());
            return new JniMethod(path, symbol, scope.binary(), method.getName().toString(), arguments, varargs(method));
        });
    }

    /**
     * Whether the last parameter is variable arity. The parse tree keeps that only as a flag, which the tree API does not
     * expose; javac prints such a parameter as {@code Type... name}, and a plain array as {@code Type[] name}.
     */
    private static boolean varargs(MethodTree method) {
        List<? extends VariableTree> params = method.getParameters();
        if (params.isEmpty()) return false;
        VariableTree last = params.get(params.size() - 1);
        return last.toString().endsWith("... " + last.getName());
    }

    /** The named classes around a path, innermost first; empty when one of them is local or anonymous. */
    private static Optional<List<ClassTree>> enclosing(TreePath path) {
        Tree leaf = path.getLeaf();
        if (leaf instanceof CompilationUnitTree) return Optional.of(new ArrayList<>());
        if (!(leaf instanceof ClassTree type) || type.getSimpleName().length() == 0) return Optional.empty();
        return enclosing(path.getParentPath()).map(outer -> {
            outer.add(0, type);
            return outer;
        });
    }

    private static Scope scope(CompilationUnitTree unit, List<ClassTree> classes, MethodTree method) {
        String pkg = packageOf(unit);
        List<String> outerFirst = new ArrayList<>(classes.stream().map(c -> c.getSimpleName().toString()).toList());
        Collections.reverse(outerFirst);
        List<String> canonical = IntStream.range(0, outerFirst.size())
                .mapToObj(i -> qualify(pkg, String.join(".", outerFirst.subList(0, i + 1))))
                .collect(Collectors.toCollection(ArrayList::new));
        Collections.reverse(canonical);
        Map<String, List<? extends Tree>> variables = new HashMap<>();
        Stream.concat(method.getTypeParameters().stream(), classes.stream().flatMap(c -> c.getTypeParameters().stream()))
                .forEach(p -> variables.putIfAbsent(p.getName().toString(), p.getBounds()));
        return new Scope(unit, pkg, canonical, prefix(pkg) + String.join("$", outerFirst), variables);
    }

    private String descriptor(Tree type, Scope s) {
        if (type instanceof PrimitiveTypeTree primitive) return primitive(primitive.getPrimitiveTypeKind());
        if (type instanceof ArrayTypeTree array) return "[" + descriptor(array.getType(), s);
        if (type instanceof ParameterizedTypeTree generic) return descriptor(generic.getType(), s);
        if (type instanceof AnnotatedTypeTree annotated) return descriptor(annotated.getUnderlyingType(), s);
        if (type instanceof IdentifierTree id && s.variables().containsKey(id.getName().toString())) {
            return erasure(id.getName().toString(), s);
        }
        return reference(type, s).filter(binary -> !binary.equals(JniMemberTypes.BLOCKED)).map(binary -> "L" + binary + ";")
                .orElse(JniNames.UNKNOWN);
    }

    private static String primitive(TypeKind kind) {
        return switch (kind) {
            case BOOLEAN -> "Z";
            case BYTE -> "B";
            case CHAR -> "C";
            case SHORT -> "S";
            case INT -> "I";
            case LONG -> "J";
            case FLOAT -> "F";
            case DOUBLE -> "D";
            default -> JniNames.UNKNOWN;
        };
    }

    /** A type variable erases to its first bound; the variable leaves the scope, so a cyclic bound cannot recurse. */
    private String erasure(String variable, Scope s) {
        List<? extends Tree> bounds = s.variables().get(variable);
        return bounds.isEmpty() ? OBJECT : descriptor(bounds.get(0), s.without(variable));
    }

    private Optional<String> reference(Tree type, Scope s) {
        if (type instanceof IdentifierTree id) return simple(id.getName().toString(), s);
        if (!(type instanceof MemberSelectTree)) return Optional.empty();
        String dotted = type.toString();
        int dot = dotted.indexOf('.');
        Optional<String> head = simple(dotted.substring(0, dot), s);
        boolean pkg = head.filter(JniMemberTypes.BLOCKED::equals).isPresent() && Character.isLowerCase(dotted.charAt(0));
        return head.isPresent() && !pkg ? head.map(h -> h.equals(JniMemberTypes.BLOCKED) ? h
                : h + "$" + dotted.substring(dot + 1).replace('.', '$')) : qualified(dotted, true);
    }

    private Optional<String> simple(String name, Scope s) {
        return first(List.of(() -> s.classes().stream().map(c -> member(c, name)).flatMap(Optional::stream).findFirst(),
                () -> imports(s.unit(), false).stream().filter(q -> q.endsWith("." + name)).findFirst()
                        .flatMap(q -> qualified(q, true)),
                () -> known(qualify(s.pkg(), name)),
                () -> onDemand(name, s.unit()),
                () -> platform("java.lang." + name, true)));
    }

    /** A member type the enclosing class declares, the class itself, or a member type it inherits. */
    private Optional<String> member(String canonical, String name) {
        return first(List.of(() -> known(canonical + "." + name),
                () -> canonical.equals(name) || canonical.endsWith("." + name) ? known(canonical) : Optional.empty(),
                () -> known(canonical).flatMap(binary -> members.inherited(binary, name))));
    }

    /** The one type an on-demand import gives the name; none when no import or more than one gives it. */
    private Optional<String> onDemand(String name, CompilationUnitTree unit) {
        List<String> found = imports(unit, true).stream().map(p -> qualified(p + "." + name, false))
                .flatMap(Optional::stream).distinct().toList();
        return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
    }

    private Optional<String> qualified(String canonical, boolean convention) {
        return first(List.of(() -> known(canonical), () -> platformNested(canonical),
                () -> convention && packageQualified(canonical)
                        ? Optional.of(canonical.replace('.', '/')) : Optional.empty()));
    }

    /** A platform class by canonical name, trying each split into package and nested classes. */
    private Optional<String> platformNested(String canonical) {
        List<String> parts = Arrays.asList(canonical.split("\\."));
        return IntStream.range(0, parts.size())
                .mapToObj(k -> String.join(".", parts.subList(0, parts.size() - k))
                        + (k == 0 ? "" : "$" + String.join("$", parts.subList(parts.size() - k, parts.size()))))
                .map(name -> platform(name, false)).flatMap(Optional::stream).findFirst();
    }

    private static boolean packageQualified(String canonical) {
        String[] parts = canonical.split("\\.");
        return parts.length > 1 && Arrays.stream(parts, 0, parts.length - 1)
                .allMatch(p -> !p.isEmpty() && Character.isLowerCase(p.charAt(0)));
    }

    private Optional<String> platform(String binaryName, boolean publicOnly) {
        return platform.computeIfAbsent(binaryName, JniTypes::load)
                .filter(c -> !publicOnly || Modifier.isPublic(c.getModifiers()))
                .map(c -> c.getName().replace('.', '/'));
    }

    static Optional<Class<?>> load(String binaryName) {
        try {
            return Optional.of(Class.forName(binaryName, false, ClassLoader.getPlatformClassLoader()));
        } catch (ClassNotFoundException | LinkageError | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Optional<String> known(String canonical) {
        return Optional.ofNullable(binaries.get(canonical));
    }

    private static List<String> imports(CompilationUnitTree unit, boolean onDemand) {
        return unit.getImports().stream().filter(i -> !i.isStatic()).map(i -> i.getQualifiedIdentifier().toString())
                .filter(q -> q.endsWith(".*") == onDemand)
                .map(q -> onDemand ? q.substring(0, q.length() - 2) : q).toList();
    }

    /** The first step that finds a value; later steps are not run. */
    private static Optional<String> first(List<Supplier<Optional<String>>> steps) {
        return steps.stream().map(Supplier::get).flatMap(Optional::stream).findFirst();
    }

    private static String packageOf(CompilationUnitTree unit) {
        return unit.getPackageName() == null ? "" : unit.getPackageName().toString();
    }

    private static String prefix(String pkg) {
        return pkg.isEmpty() ? "" : pkg.replace('.', '/') + "/";
    }

    private static String qualify(String outer, String name) {
        return outer.isEmpty() ? name : outer + "." + name;
    }
}
