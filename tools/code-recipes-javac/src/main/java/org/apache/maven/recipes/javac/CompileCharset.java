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

import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.Optional;

import com.sun.tools.javac.file.BaseFileManager;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Options;

/**
 * The charset javac decodes a source file with. javac's file manager decodes it ({@code BaseFileManager.decode}): with
 * the charset given to {@code getStandardFileManager} when there is one, as maven-compiler-plugin gives it in process,
 * else with its {@code -encoding} option. Neither is in the compiler's {@link Options} of a javax.tools compile, and
 * with {@code --release} javac hides its file manager behind a delegating one
 * ({@code DelegatingJavaFileManager.installReleaseFileManager}, JDK 21). The file object itself still knows: javac's
 * {@code PathFileObject.openReader} wraps the file in an {@link InputStreamReader} over its file manager's decoder,
 * whose encoding is read without reading the file. Other file objects fall back to the file manager, the options and
 * the platform default.
 */
final class CompileCharset {
    private static final String ENCODING = "-encoding";

    private CompileCharset() {}

    static Charset of(JavaFileObject file, Context context) {
        return ofReader(file).orElseGet(() -> ofContext(context));
    }

    /** The charset of the reader javac's file object opens, which decodes as javac's file manager does. */
    private static Optional<Charset> ofReader(JavaFileObject file) {
        try (Reader reader = file.openReader(false)) {
            return reader instanceof InputStreamReader decoding
                    ? Optional.of(Charset.forName(decoding.getEncoding()))
                    : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Charset ofContext(Context context) {
        if (context.get(JavaFileManager.class) instanceof BaseFileManager files) {
            return files.getDecoder(files.getEncodingName(), false).charset();
        }
        String encoding = Options.instance(context).get(ENCODING);
        return encoding == null ? Charset.defaultCharset() : Charset.forName(encoding);
    }
}
