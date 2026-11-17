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
package org.apache.maven.recipes.javac;

import org.openrewrite.java.tree.J;

/**
 * One converted compilation unit.
 *
 * @param lst the OpenRewrite tree
 * @param printEqual whether the tree prints the exact text javac parsed and, for a file on disk, its exact bytes
 * @param calls how many method calls, constructor calls and method references carry a method type
 * @param detail why the tree is not usable although it was built, or empty
 */
public record LstResult(J.CompilationUnit lst, boolean printEqual, MethodCalls calls, String detail) {}
