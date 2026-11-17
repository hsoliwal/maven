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
package org.apache.maven.plugins.clustering.lsp;

import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.eclipse.lsp4j.SymbolKind;

/**
 * Which document symbols become atoms, per kind of language. The rules follow what the servers were seen to return:
 * <ul>
 *   <li>{@link #CODE} (Java, C, C++, Python, Rust and every language not listed below): Class, Interface, Struct, Enum
 *       and Object symbols are TYPE atoms; Method, Function and Constructor symbols are METHOD atoms. A qualified name
 *       ({@code Point::norm}, {@code sum(int[])}) is split into owner and name.</li>
 *   <li>{@link #MARKUP} (XML, HTML): LemMinX and the HTML server report elements as Field symbols. An element with
 *       child elements is a TYPE atom; a leaf element, an attribute and the XML declaration (Property) are not.
 *       Functions in embedded scripts are METHOD atoms.</li>
 *   <li>{@link #STYLE} (CSS, SCSS, Less): every rule (a Class symbol) is a TYPE atom, nested or not; at-rules such as
 *       {@code @media} (Module) are not atoms, but the rules inside them are.</li>
 *   <li>{@link #DATA} (JSON): an object with members (a Module or Object symbol with children) is a TYPE atom;
 *       arrays and plain values are not, but objects inside arrays are.</li>
 * </ul>
 * Only code names are split: element names, selectors ({@code .card .title}, {@code div#main.card}) and keys are
 * kept as the server reports them. Children of a symbol that is not an atom are still visited.
 */
enum SymbolMapping {
    CODE, MARKUP, STYLE, DATA;

    private static final Set<SymbolKind> TYPES = Set.of(SymbolKind.Class, SymbolKind.Interface, SymbolKind.Struct,
            SymbolKind.Enum, SymbolKind.Object);
    private static final Set<SymbolKind> METHODS = Set.of(SymbolKind.Method, SymbolKind.Function, SymbolKind.Constructor);
    private static final Set<SymbolKind> ELEMENTS = Set.of(SymbolKind.Field, SymbolKind.Module, SymbolKind.Namespace,
            SymbolKind.Package, SymbolKind.Object, SymbolKind.Struct, SymbolKind.Class);
    private static final Set<SymbolKind> OBJECTS = Set.of(SymbolKind.Module, SymbolKind.Object);

    /** TYPE, METHOD or nothing; {@code children} is asked only when the rule depends on it. */
    Optional<String> kind(SymbolKind kind, BooleanSupplier children) {
        boolean method = METHODS.contains(kind);
        boolean type = switch (this) {
            case CODE -> TYPES.contains(kind);
            case MARKUP -> !method && ELEMENTS.contains(kind) && children.getAsBoolean();
            case STYLE -> kind == SymbolKind.Class;
            case DATA -> OBJECTS.contains(kind) && children.getAsBoolean();
        };
        if (type) return Optional.of("TYPE");
        return method && this != DATA ? Optional.of("METHOD") : Optional.empty();
    }

    boolean splitsNames() {
        return this == CODE;
    }

    /** The mapping for an LSP languageId, such as {@code xml}, {@code css} or {@code json}. */
    static SymbolMapping forLanguage(String languageId) {
        return switch (languageId) {
            case "xml", "xsd", "xsl", "html", "xhtml" -> MARKUP;
            case "css", "scss", "less", "sass" -> STYLE;
            case "json", "jsonc" -> DATA;
            default -> CODE;
        };
    }
}
