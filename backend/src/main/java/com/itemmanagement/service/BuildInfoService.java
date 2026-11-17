package com.itemmanagement.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

/**
 * Build metadata split by audience. The public half is intentionally limited to
 * the application name and version; commit and build timestamp are only exposed
 * to platform administrators so unauthenticated callers cannot fingerprint the
 * exact deployed revision.
 */
@Service
public class BuildInfoService {

    private final ObjectProvider<BuildProperties> buildProperties;
    private final String configuredGitCommit;

    public BuildInfoService(ObjectProvider<BuildProperties> buildProperties,
                            @Value("${app.build.git-commit:unknown}") String configuredGitCommit) {
        this.buildProperties = buildProperties;
        this.configuredGitCommit = configuredGitCommit;
    }

    public String name() {
        return buildInfo("build.name", "item-management-system");
    }

    public String version() {
        return buildInfo("build.version", "unknown");
    }

    public String buildTime() {
        return buildInfo("build.time", "unknown");
    }

    public String gitCommit() {
        if (configuredGitCommit != null && !configuredGitCommit.isBlank()
                && !"unknown".equals(configuredGitCommit)) {
            return configuredGitCommit;
        }
        BuildProperties properties = buildProperties.getIfAvailable();
        String commit = properties == null ? null : properties.get("git.commit");
        return commit == null || commit.isBlank() ? "unknown" : commit;
    }

    private String buildInfo(String key, String fallback) {
        BuildProperties properties = buildProperties.getIfAvailable();
        if (properties == null) {
            return fallback;
        }
        String suffix = key.substring("build.".length());
        String value = switch (suffix) {
            case "name" -> properties.getName();
            case "version" -> properties.getVersion();
            case "artifact" -> properties.getArtifact();
            case "time" -> properties.getTime() == null ? null : properties.getTime().toString();
            default -> properties.get(suffix);
        };
        return value == null || value.isBlank() ? fallback : value;
    }
}
