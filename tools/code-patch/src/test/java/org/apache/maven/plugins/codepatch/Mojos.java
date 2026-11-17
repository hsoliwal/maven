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
package org.apache.maven.plugins.codepatch;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.maven.plugin.logging.Log;

/**
 * Runs a goal the way Maven would, for the tests: sets its parameter fields, captures its log, and returns how it
 * ended ({@code OK}, or the exception's simple class name and message).
 */
final class Mojos {
    private Mojos() { }

    /** A goal run: how it ended and every line it logged, as {@code level text}. */
    record Run(String outcome, List<String> log) {
        String logged() {
            return String.join("\n", log);
        }
    }

    static Run run(AbstractPatchMojo mojo, Path base, Path out, List<Path> patches, String field, Object value)
            throws ReflectiveOperationException {
        configure(mojo, base, out, patches);
        if (field != null) set(mojo, field, value);
        List<String> log = new ArrayList<>();
        mojo.setLog(capture(log));
        try {
            mojo.execute();
            return new Run("OK", log);
        } catch (Exception e) {
            return new Run(e.getClass().getSimpleName() + ": " + e.getMessage(), log);
        }
    }

    private static void configure(AbstractPatchMojo mojo, Path base, Path out, List<Path> patches)
            throws ReflectiveOperationException {
        set(mojo, "baseDirectory", base.toFile());
        set(mojo, "executionRootDirectory", base.toFile());
        set(mojo, "outputDirectory", out.toFile());
        set(mojo, "patches", patches.stream().map(Path::toFile).collect(Collectors.toList()));
    }

    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (!field.getName().equals(name)) continue;
                field.setAccessible(true);
                field.set(target, value);
                return;
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Log capture(List<String> log) {
        return (Log) Proxy.newProxyInstance(Log.class.getClassLoader(), new Class<?>[] {Log.class}, (proxy, method, args) -> {
            if (method.getName().startsWith("is")) return true;
            if (args != null && args.length > 0 && args[0] instanceof CharSequence) log.add(method.getName() + " " + args[0]);
            return null;
        });
    }
}
