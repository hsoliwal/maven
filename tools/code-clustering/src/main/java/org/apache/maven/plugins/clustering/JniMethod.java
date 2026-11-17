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

/**
 * A Java method declared {@code native}: its atom ({@code path}, {@code symbol}), the binary name of its class in
 * internal form ({@code p/Outer$Inner}), its name, and its argument descriptor, in which a reference type that could
 * not be resolved without a class path is {@link JniNames#UNKNOWN}.
 */
record JniMethod(String path, String symbol, String className, String method, String arguments) {
    static final String UNRESOLVED = "UNRESOLVED";

    String shortName() {
        return JniNames.shortName(className, method);
    }

    boolean resolved() {
        return !arguments.contains(JniNames.UNKNOWN);
    }

    /** The long name, or {@value #UNRESOLVED} when an argument type could not be resolved. */
    String longName() {
        return resolved() ? JniNames.longName(shortName(), arguments) : UNRESOLVED;
    }

    /** Whether a function name is this method's long name; any class name stands for an unresolved argument type. */
    boolean longMatches(String function) {
        return resolved() ? function.equals(longName())
                : JniNames.longPattern(shortName(), arguments).matcher(function).matches();
    }
}
