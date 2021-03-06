package com.github.andirady.pomcli;

import static java.util.stream.Collectors.joining;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

import java.util.stream.Stream;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.DefaultModelReader;
import org.apache.maven.model.io.DefaultModelWriter;
import org.apache.maven.model.io.ModelReader;

import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExecutionException;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "add", sortOptions = false)
public class AddCommand implements Runnable {

    private static final Logger LOG = Logger.getLogger("add");

    static class Scope {

        @Option(names = "--compile", description = "Add as compile dependency. This is the default", order = 0)
        boolean compile;

        @Option(names = "--runtime", description = "Add as runtime dependency", order = 1)
        boolean runtime;

        @Option(names = "--provided", description = "Add as provided dependency", order = 2)
        boolean provided;

        @Option(names = "--test", description = "Add as test dependency", order = 3)
        boolean test;

        @Option(names = "--import", description = "Add as import dependency", order = 4)
        boolean importScope;

        String value() {
            if (runtime)     return "runtime";
            if (provided)    return "provided";
            if (test)        return "test";
            if (importScope) return "import";

            return "compile";
        }

    }

    @Option(names = { "-f", "--file" }, defaultValue = "pom.xml", order = 0)
    Path pomPath;

    @ArgGroup(exclusive = true, multiplicity = "0..1", order = 1)
    Scope scope;

    @Parameters(
        arity = "1..*",
        paramLabel = "DEPENDENCY",
        description = """
                      groupId:artifactId[:version] or path to either \
                      a directory, pom.xml, or a jar file."""
    )
    List<Dependency> coords;

    @Spec
    CommandSpec spec;

    private Model model;
    private Model parentPom;

    @Override
    public void run() {
        var reader = new DefaultModelReader();
        if (Files.exists(pomPath)) {
            try (var is = Files.newInputStream(pomPath)) {
                model = reader.read(is, null);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } else {
            LOG.fine(() -> pomPath + " does not exists. Creating a new one");
            model = new NewPom().newPom(pomPath);
            model.setArtifactId(Path.of(System.getProperty("user.dir")).getFileName().toString());
            if (model.getParent() == null) {
                model.setGroupId("unnamed");
                model.setVersion("0.0.1-SNAPSHOT");
            }
        }

        readParentPom(reader);

        var existing = getExistingDependencies();
        var duplicates = coords.stream()
                               .filter(c -> existing.stream().anyMatch(d -> sameArtifact(c, d, false)))
                               .map(this::coordString)
                               .collect(joining(", "));
        if (duplicates.length() > 0) {
            throw new IllegalArgumentException("Duplicate artifact(s): " + duplicates);
        }

        var stream = coords.stream().parallel().map(this::ensureVersion);
        if (scope != null && !"compile".equals(scope.value())) {
            stream = stream.map(d -> {
                d.setScope(scope.value());
                if (scope.importScope) {
                    d.setType("pom");
                }
                return d;
            });
        }

        var deps = stream.toList();
        existing.addAll(deps);
	
        var writer = new DefaultModelWriter();
		try (var os = Files.newOutputStream(pomPath)) {
            writer.write(os, null, model);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    void readParentPom(ModelReader reader) {
        if (model.getParent() != null) {
            var parentRelativePath = model.getParent().getRelativePath();
            var parentPomPath = pomPath.toAbsolutePath().getParent().resolve(parentRelativePath);
            var filename = "pom.xml";
            if (!parentPomPath.getFileName().toString().equals(filename)) {
                parentPomPath = parentPomPath.resolve(filename);
            }
            try (var is = Files.newInputStream(parentPomPath)) {
                parentPom = reader.read(is, null);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    List<Dependency> getExistingDependencies() {
        if (!"pom".equals(model.getPackaging())) {
            return model.getDependencies();
        }

        var dm = model.getDependencyManagement();
        if (dm == null) {
            dm = new DependencyManagement();
            dm.setDependencies(new ArrayList<>());
            model.setDependencyManagement(dm);
        }

        return dm.getDependencies();
    }

	Dependency ensureVersion(Dependency dep) {
		if (dep.getVersion() != null) {
            return dep;
        }

        DependencyManagement dm = null;
        Optional<Dependency> managed = null;
        if (
            parentPom != null
                && (dm = parentPom.getDependencyManagement()) != null
                && (managed = dm.getDependencies().stream().filter(d -> sameArtifact(d, dep, true)).findFirst()).isPresent()
        ) {
            if (dep.getGroupId() == null) {
                dep.setGroupId(managed.get().getGroupId());
            }
            return dep;
        }

        var latestVersion = new GetLatestVersion()
                .execute(new QuerySpec(dep.getGroupId(), dep.getArtifactId(), null))
                .orElseThrow(() -> new IllegalStateException(
                        "No version found: '" + dep.getGroupId() + ":" + dep.getArtifactId() + "'"
                    ));
        dep.setVersion(latestVersion);

        return dep;
	}

    boolean sameArtifact(Dependency d1, Dependency d2, boolean ignoreGroupId) {
        if (!ignoreGroupId && !Objects.equals(d1.getGroupId(), d2.getGroupId())) {
            return false;
        }

        return d1.getArtifactId().equals(d2.getArtifactId()) && Objects.equals(d1.getClassifier(), d2.getClassifier());
    }

    String coordString(Dependency d) {
        return d.getGroupId() + ":"
             + d.getArtifactId()
             + Optional.ofNullable(d.getClassifier()).map(c -> ":" + c).orElse("");
    }

}
