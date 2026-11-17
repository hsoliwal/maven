/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * The NuGet packages {@code dotnet pack} wrote, named for Maven: each {@code .nupkg} or {@code .snupkg} becomes an
 * attached artifact whose type is its extension and whose classifier is the package id from the {@code .nuspec}
 * at the root of the package (a zip). The version of the package stays the NuGet version inside it.
 */
final class NugetPackages {
    static final List<String> TYPES = List.of("nupkg", "snupkg");
    private static final int MAX_NUSPEC = 1024 * 1024;

    /** One package file and its identity. */
    record Package(Path file, String type, String id, String version) { }

    private NugetPackages() { }

    /** The packages directly in {@code directory}, in name order; none when it is missing. */
    static List<Package> list(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        List<Package> packages = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.sorted().toList()) {
                String type = type(file);
                if (type != null && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) packages.add(read(file, type));
            }
        }
        return List.copyOf(packages);
    }

    /** The packages unchanged, unless two share an id and a type (an old version left in the folder). */
    static List<Package> unique(List<Package> packages) throws IOException {
        Set<String> seen = new HashSet<>();
        for (Package nuget : packages) {
            if (!seen.add(nuget.type() + ":" + nuget.id())) {
                throw new IOException("more than one " + nuget.type() + " of package " + nuget.id()
                        + " in the folder; remove the old ones (mvn clean)");
            }
        }
        return packages;
    }

    /** The package type (extension) of a file name, or null for other files. */
    static String type(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return TYPES.stream().filter(type -> name.endsWith("." + type)).findFirst().orElse(null);
    }

    static Package read(Path file, String type) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry spec = zip.stream().filter(entry -> !entry.getName().contains("/")
                    && entry.getName().endsWith(".nuspec")).findFirst()
                    .orElseThrow(() -> new IOException("no .nuspec at the root of " + file));
            try (InputStream in = zip.getInputStream(spec)) {
                Document nuspec = parse(in.readNBytes(MAX_NUSPEC + 1), file);
                return new Package(file, type, metadata(nuspec, "id", file), metadata(nuspec, "version", file));
            }
        }
    }

    private static Document parse(byte[] bytes, Path file) throws IOException {
        if (bytes.length > MAX_NUSPEC) throw new IOException(".nuspec over 1 MiB in " + file);
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (ParserConfigurationException | SAXException failure) {
            throw new IOException("unreadable .nuspec in " + file + ": " + failure.getMessage(), failure);
        }
    }

    /** A metadata value; the nuspec namespace differs between schema versions, so only the local name counts. */
    private static String metadata(Document nuspec, String name, Path file) throws IOException {
        NodeList found = nuspec.getElementsByTagNameNS("*", name);
        String value = found.getLength() == 0 ? "" : found.item(0).getTextContent().strip();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,199}")) {
            throw new IOException("invalid NuGet " + name + " '" + value + "' in " + file);
        }
        return value;
    }
}
