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
package org.apache.maven.project;

import java.util.List;
import java.util.Map;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.metadata.ArtifactMetadata;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout2;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.bridge.MavenRepositorySystem;
import org.apache.maven.model.Repository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@code <repository><layout>p2</layout>} in a POM (Eclipse builds name their p2 sites this way: corrosion's
 * {@code justj}, egit's {@code jgit} and {@code eclipse.license}) must reach the project with the layout the project's
 * extension registered for it. Tycho reads its p2 sites from {@link MavenProject#getRemoteArtifactRepositories()} with
 * {@code instanceof P2ArtifactRepositoryLayout}; with the default layout, or with the copy of the layout class loaded
 * by Tycho's core extension, the sites never reached Tycho and Maven itself downloaded artifacts from them.
 */
class PomRepositoryLayoutTest {

    private final MavenRepositorySystem repositorySystem = new MavenRepositorySystem(
            null, Map.of("default", new DefaultRepositoryLayout(), P2LikeLayout.ID, new P2LikeLayout()));

    @Test
    void projectRepositoriesKeepTheLayoutTheyDeclare() throws Exception {
        List<ArtifactRepository> repositories = helper(new P2LikeLayout())
                .createArtifactRepositories(
                        List.of(repository("jgit", P2LikeLayout.ID), repository("central-copy", null)),
                        List.of(),
                        new DefaultProjectBuildingRequest());

        assertEquals(P2LikeLayout.class, repositories.get(0).getLayout().getClass());
        assertFalse(repositories.get(0).getReleases().isEnabled(), "p2 sites stay out of Maven resolution");
        assertFalse(repositories.get(0).getSnapshots().isEnabled(), "p2 sites stay out of Maven resolution");
        assertEquals(
                DefaultRepositoryLayout.class, repositories.get(1).getLayout().getClass());
    }

    @Test
    void theLayoutOfTheProjectRealmWinsOverACopyFromAnotherRealm() throws Exception {
        DefaultProjectBuildingHelper helper = helper(new CoreExtensionCopy(), new P2LikeLayout());
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(new HidingClassLoader(previous, CoreExtensionCopy.class.getName()));
        try {
            List<ArtifactRepository> repositories = helper.createArtifactRepositories(
                    List.of(repository("justj", P2LikeLayout.ID)), List.of(), new DefaultProjectBuildingRequest());

            assertEquals(P2LikeLayout.class, repositories.get(0).getLayout().getClass());
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    void unknownLayoutsKeepTheDefaultLayout() throws Exception {
        ArtifactRepository repository = repositorySystem.buildArtifactRepositoryWithLayout(repository("x", "unknown"));

        assertEquals(DefaultRepositoryLayout.class, repository.getLayout().getClass());
    }

    private DefaultProjectBuildingHelper helper(ArtifactRepositoryLayout... layouts) {
        return new DefaultProjectBuildingHelper(null, null, repositorySystem, null, List.of(layouts));
    }

    private static Repository repository(String id, String layout) {
        Repository repository = new Repository();
        repository.setId(id);
        repository.setUrl("https://repo.example.org/" + id);
        if (layout != null) {
            repository.setLayout(layout);
        }
        return repository;
    }

    /** The shape of Tycho's {@code P2ArtifactRepositoryLayout}: a marker layout whose repositories are disabled. */
    static class P2LikeLayout implements ArtifactRepositoryLayout, ArtifactRepositoryLayout2 {
        static final String ID = "p2";
        private static final ArtifactRepositoryPolicy DISABLED = new ArtifactRepositoryPolicy(
                false, ArtifactRepositoryPolicy.UPDATE_POLICY_NEVER, ArtifactRepositoryPolicy.CHECKSUM_POLICY_IGNORE);

        @Override
        public String getId() {
            return ID;
        }

        @Override
        public String pathOf(Artifact artifact) {
            return ".p2-ignore";
        }

        @Override
        public String pathOfLocalRepositoryMetadata(ArtifactMetadata metadata, ArtifactRepository repository) {
            return ".p2-ignore";
        }

        @Override
        public String pathOfRemoteRepositoryMetadata(ArtifactMetadata metadata) {
            return ".p2-ignore";
        }

        @Override
        public ArtifactRepository newMavenArtifactRepository(
                String id, String url, ArtifactRepositoryPolicy snapshots, ArtifactRepositoryPolicy releases) {
            return new MavenArtifactRepository(id, url, this, DISABLED, DISABLED);
        }
    }

    /** Stands for the same layout loaded a second time by another realm (Tycho's core extension). */
    static final class CoreExtensionCopy extends P2LikeLayout {}

    /** A project realm that does not see the core extension's classes. */
    static final class HidingClassLoader extends ClassLoader {
        private final String hidden;

        HidingClassLoader(ClassLoader parent, String hidden) {
            super(parent);
            this.hidden = hidden;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (hidden.equals(name)) {
                throw new ClassNotFoundException(name);
            }
            return super.loadClass(name, resolve);
        }
    }
}
