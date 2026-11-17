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
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The member types a class inherits, for the simple-name step of {@link JniTypes}: the member types (not private) that
 * its superclass and superinterfaces declare or inherit in turn. Supertypes are known from the compilation units of the
 * scan, whose {@code extends} and {@code implements} clauses resolve in the scope around the class, and from the
 * platform classes of the running JDK ({@code Class.getDeclaredClasses}, public and protected, up the superclass and
 * interface chain). An enum's superclass is {@code java.lang.Enum}, a record's {@code java.lang.Record}. A supertype
 * that neither knows may hold the member type itself, so when no known supertype has it the answer is
 * {@link #BLOCKED}: the name cannot be resolved and the long name is UNRESOLVED.
 */
final class JniMemberTypes {
    /** The binary name standing for "may be a member type of a supertype that is not known". */
    static final String BLOCKED = "?";

    /** A class of the scan: its tree, its compilation unit, and its enclosing classes by canonical name, innermost first. */
    record Declaration(ClassTree tree, CompilationUnitTree unit, List<String> outers) { }

    private final Map<String, Declaration> declarations = new HashMap<>();
    private final Map<String, List<Optional<String>>> supertypes = new HashMap<>();
    private final Set<String> resolving = new HashSet<>();
    private final BiFunction<Tree, Declaration, Optional<String>> resolve;
    private final Function<String, Optional<Class<?>>> platform;

    /**
     * @param resolve  the binary name of a type tree in the scope around a declaration
     * @param platform a platform class by binary name ({@code java.util.AbstractMap})
     */
    JniMemberTypes(BiFunction<Tree, Declaration, Optional<String>> resolve, Function<String, Optional<Class<?>>> platform) {
        this.resolve = resolve;
        this.platform = platform;
    }

    void declare(String binary, Declaration declaration) {
        declarations.put(binary, declaration);
    }

    /** The member type {@code name} that the class with this binary name inherits; {@link #BLOCKED} or empty. */
    Optional<String> inherited(String binary, String name) {
        return inherited(binary, name, new HashSet<>(Set.of(binary)));
    }

    private Optional<String> inherited(String binary, String name, Set<String> seen) {
        return combine(supertypes(binary).stream()
                .map(s -> s.filter(b -> !b.equals(BLOCKED)).map(b -> member(b, name, seen)).orElse(Optional.of(BLOCKED))).toList());
    }

    /** The member type {@code name} of a supertype: declared (not private) or inherited. */
    private Optional<String> member(String binary, String name, Set<String> seen) {
        if (!seen.add(binary)) return Optional.empty();
        Declaration d = declarations.get(binary);
        if (d != null) return declared(d.tree(), binary, name).or(() -> inherited(binary, name, seen));
        return platform.apply(binary.replace('/', '.')).map(c -> platformMember(c, name)).orElse(Optional.of(BLOCKED));
    }

    /** The first member type found; else {@link #BLOCKED} when a supertype is not known; else empty. */
    private static Optional<String> combine(List<Optional<String>> found) {
        Optional<String> known = found.stream().flatMap(Optional::stream).filter(b -> !b.equals(BLOCKED)).findFirst();
        return known.isPresent() || found.stream().noneMatch(Optional::isPresent) ? known : Optional.of(BLOCKED);
    }

    private static Optional<String> declared(ClassTree tree, String binary, String name) {
        return tree.getMembers().stream().filter(ClassTree.class::isInstance).map(ClassTree.class::cast)
                .filter(c -> c.getSimpleName().contentEquals(name)
                        && !c.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.PRIVATE))
                .findFirst().map(c -> binary + "$" + name);
    }

    private static Optional<String> platformMember(Class<?> type, String name) {
        Optional<String> own = Arrays.stream(declaredClasses(type)).filter(c -> c.getSimpleName().equals(name)
                && (Modifier.isPublic(c.getModifiers()) || Modifier.isProtected(c.getModifiers())))
                .findFirst().map(c -> c.getName().replace('.', '/'));
        return own.or(() -> Stream.concat(Stream.ofNullable(type.getSuperclass()), Arrays.stream(type.getInterfaces()))
                .map(s -> platformMember(s, name)).flatMap(Optional::stream).findFirst());
    }

    private static Class<?>[] declaredClasses(Class<?> type) {
        try {
            return type.getDeclaredClasses();
        } catch (LinkageError | SecurityException e) {
            return new Class<?>[0];
        }
    }

    /** The binary names of a scanned class's supertypes; empty for one that does not resolve, or while it resolves. */
    private List<Optional<String>> supertypes(String binary) {
        List<Optional<String>> known = supertypes.get(binary);
        if (known != null) return known;
        Declaration d = declarations.get(binary);
        if (d == null || !resolving.add(binary)) return List.of(Optional.empty());
        ClassTree tree = d.tree();
        List<Optional<String>> found = Stream.concat(Stream.concat(Stream.ofNullable(tree.getExtendsClause()),
                tree.getImplementsClause().stream()).map(t -> resolve.apply(base(t), d)), implicit(tree)).toList();
        resolving.remove(binary);
        supertypes.put(binary, found);
        return found;
    }

    private static Stream<Optional<String>> implicit(ClassTree tree) {
        return switch (tree.getKind()) {
            case ENUM -> Stream.of(Optional.of("java/lang/Enum"));
            case RECORD -> Stream.of(Optional.of("java/lang/Record"));
            default -> Stream.empty();
        };
    }

    /** The type a supertype clause names, without type arguments and annotations. */
    private static Tree base(Tree type) {
        if (type instanceof ParameterizedTypeTree generic) return base(generic.getType());
        if (type instanceof AnnotatedTypeTree annotated) return base(annotated.getUnderlyingType());
        return type;
    }
}
