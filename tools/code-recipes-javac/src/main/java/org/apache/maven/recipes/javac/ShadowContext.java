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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.sun.tools.javac.util.Context;

/**
 * The context rewrite's Javadoc visitor gets: every key answers from the compile's context except those put here,
 * which is only {@link PureDocAttr}. The visitor takes {@code Attr.instance(context)} from it, so javac's own Attr,
 * which reports into javac's Log and completes classes javac never read, never runs for the conversion.
 */
final class ShadowContext extends Context {
    private final Context build;
    private final Map<Key<?>, Object> own = new HashMap<>();
    private final Map<Class<?>, Object> ownByClass = new HashMap<>();

    private ShadowContext(Context build) {
        this.build = build;
    }

    /** A shadow of the compile's context with a {@link PureDocAttr}; empty if javac's Attr cannot be shadowed. */
    static Optional<Context> of(Context build) {
        try {
            ShadowContext shadow = new ShadowContext(build);
            new PureDocAttr(shadow, build);
            return Optional.of(shadow);
        } catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }

    @Override
    public <T> void put(Key<T> key, T data) {
        own.put(key, data);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Key<T> key) {
        Object value = own.get(key);
        return value != null ? (T) value : build.get(key);
    }

    @Override
    public <T> void put(Class<T> clazz, T data) {
        ownByClass.put(clazz, data);
    }

    @Override
    public <T> T get(Class<T> clazz) {
        Object value = ownByClass.get(clazz);
        return value != null ? clazz.cast(value) : build.get(clazz);
    }
}
