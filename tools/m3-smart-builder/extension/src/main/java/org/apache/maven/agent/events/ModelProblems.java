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
package org.apache.maven.agent.events;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelProblem;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingResult;

/**
 * The located problems of a POM or model failure: the {@link ModelProblem}s of a {@link ProjectBuildingException}
 * (one result per POM) or a {@link ModelBuildingException} anywhere in the first causes, as raw diagnostics with the
 * POM file, line and column, so the digest keeps what the exception's first line leaves out.
 */
final class ModelProblems {
    /** Causes below a failure that are searched for model problems. */
    static final int MAX_DEPTH = 5;

    private ModelProblems() {}

    /** The model problems of {@code cause}, errors and fatal errors as ERROR, warnings as WARNING; else empty. */
    static List<DiagnosticLines.Raw> of(Throwable cause) {
        return Stream.iterate(cause, Objects::nonNull, Throwable::getCause).limit(MAX_DEPTH)
                .flatMap(ModelProblems::problems).map(ModelProblems::raw).distinct().collect(Collectors.toList());
    }

    private static Stream<ModelProblem> problems(Throwable failure) {
        if (failure instanceof ProjectBuildingException) {
            return Optional.ofNullable(((ProjectBuildingException) failure).getResults()).orElse(List.of()).stream()
                    .map(ProjectBuildingResult::getProblems).filter(Objects::nonNull).flatMap(List::stream);
        }
        return failure instanceof ModelBuildingException
                ? ((ModelBuildingException) failure).getProblems().stream() : Stream.empty();
    }

    private static DiagnosticLines.Raw raw(ModelProblem problem) {
        String severity = problem.getSeverity() == ModelProblem.Severity.WARNING ? DiagnosticLines.WARNING
                : DiagnosticLines.ERROR;
        String message = Optional.ofNullable(problem.getMessage()).orElse("model problem");
        return new DiagnosticLines.Raw(severity, Optional.ofNullable(problem.getSource()).orElse(""),
                Math.max(0, problem.getLineNumber()), Math.max(0, problem.getColumnNumber()), "", message, "", null);
    }
}
