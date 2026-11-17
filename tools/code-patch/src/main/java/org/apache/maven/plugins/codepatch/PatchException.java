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

/**
 * A refusal: the patch cannot be read or does not apply, a path is unsafe, an approval is stale, or a file no longer
 * has the content a receipt expects. The message names the file, and the hunk when there is one.
 */
public final class PatchException extends Exception {
    private static final long serialVersionUID = 1L;

    public PatchException(String message) {
        super(message);
    }

    public PatchException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * A failure for a message: a refusal's own message, any other exception with its type (the message of a file
     * system exception is often only a path).
     */
    static String describe(Throwable failure) {
        return failure instanceof PatchException ? failure.getMessage() : failure.toString();
    }
}
