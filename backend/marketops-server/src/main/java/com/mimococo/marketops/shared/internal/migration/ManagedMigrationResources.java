package com.mimococo.marketops.shared.internal.migration;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import org.flywaydb.core.api.ResourceProvider;
import org.flywaydb.core.api.resource.LoadableResource;

/** Closed classpath inventory that hides only canonical V0002 from the SQL resolver. */
final class ManagedMigrationResources implements ResourceProvider {
    static final String V0002 = "db/migration/V0002__enable_btree_gist_extension.sql";
    private final java.util.SortedMap<String,org.springframework.core.io.Resource> inventory = new java.util.TreeMap<>();

    ManagedMigrationResources(ClassLoader loader) {
        try {
            var scanner = new org.springframework.core.io.support.PathMatchingResourcePatternResolver(loader);
            for (var resource : scanner.getResources("classpath*:db/migration/*.sql")) {
                String filename = resource.getFilename();
                if (filename == null || !filename.matches("V[0-9]{4}__[a-z0-9_]+[.]sql")
                        || inventory.putIfAbsent(filename, resource) != null) {
                    throw new IllegalStateException("Ambiguous canonical migration inventory");
                }
            }
            int expected = 1;
            for (String filename : inventory.keySet()) {
                if (!filename.startsWith(String.format(java.util.Locale.ROOT, "V%04d__", expected++))) {
                    throw new IllegalStateException("Canonical migration inventory has a gap");
                }
            }
            if (inventory.size() < 10) throw new IllegalStateException("Protected migration inventory incomplete");
        } catch (java.io.IOException unavailable) {
            throw new IllegalStateException("Canonical migration inventory unavailable", unavailable);
        }
    }

    LoadableResource canonicalV0002() { return resource(V0002); }

    @Override
    public LoadableResource getResource(String name) {
        String normalized = name.startsWith("/") ? name.substring(1) : name;
        String filename = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (filename.startsWith("V0002__")) return null;
        return inventory.containsKey(filename) ? resource("db/migration/" + filename) : null;
    }

    @Override
    public Collection<LoadableResource> getResources(String prefix, String[] suffixes) {
        return inventory.keySet().stream().filter(name -> name.startsWith(prefix) && !name.startsWith("V0002__"))
                .filter(name -> java.util.Arrays.stream(suffixes).anyMatch(name::endsWith))
                .map(name -> resource("db/migration/" + name)).toList();
    }

    private LoadableResource resource(String path) {
        var resource = inventory.get(path.substring(path.lastIndexOf('/') + 1));
        if (resource == null) throw new IllegalStateException("Canonical migration inventory incomplete");
        return new ClasspathResource(resource, path);
    }

    private static final class ClasspathResource extends LoadableResource {
        private final org.springframework.core.io.Resource resource;
        private final String path;
        private ClasspathResource(org.springframework.core.io.Resource resource, String path) { this.resource = resource; this.path = path; }
        @Override public Reader read() {
            try { return new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8); }
            catch (java.io.IOException unavailable) { throw new IllegalStateException("Canonical migration resource absent", unavailable); }
        }
        @Override public String getAbsolutePath() { return "classpath:" + path; }
        @Override public String getAbsolutePathOnDisk() { return null; }
        @Override public String getFilename() { return path.substring(path.lastIndexOf('/') + 1); }
        @Override public String getRelativePath() { return path; }
    }
}
