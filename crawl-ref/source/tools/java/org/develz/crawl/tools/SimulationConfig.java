package org.develz.crawl.tools;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Configuration container for dungeon map simulation.
 */
public final class SimulationConfig {
    private final int depth;
    private final long seed;
    private final Path sourceRoot;
    private final Optional<String> branch;
    private final Optional<String> mapName;
    private final int width;
    private final int height;

    private SimulationConfig(Builder builder) {
        this.depth = builder.depth;
        this.seed = builder.seed;
        this.sourceRoot = builder.sourceRoot;
        this.branch = builder.branch.map(String::trim).filter(s -> !s.isEmpty());
        this.mapName = builder.mapName.map(String::trim).filter(s -> !s.isEmpty());
        this.width = builder.width;
        this.height = builder.height;
    }

    public int depth() {
        return depth;
    }

    public long seed() {
        return seed;
    }

    public Path sourceRoot() {
        return sourceRoot;
    }

    public Optional<String> branch() {
        return branch;
    }

    public Optional<String> mapName() {
        return mapName;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int depth = 1;
        private long seed = 0L;
        private Path sourceRoot;
        private Optional<String> branch = Optional.empty();
        private Optional<String> mapName = Optional.empty();
        private int width = 100;
        private int height = 100;

        private Builder() {
        }

        public Builder depth(int depth) {
            this.depth = Math.max(1, depth);
            return this;
        }

        public Builder seed(long seed) {
            this.seed = seed;
            return this;
        }

        public Builder sourceRoot(Path sourceRoot) {
            this.sourceRoot = Objects.requireNonNull(sourceRoot, "sourceRoot");
            return this;
        }

        public Builder branch(String branch) {
            this.branch = Optional.ofNullable(branch);
            return this;
        }

        public Builder mapName(String mapName) {
            this.mapName = Optional.ofNullable(mapName);
            return this;
        }

        public Builder width(int width) {
            this.width = Math.max(5, width);
            return this;
        }

        public Builder height(int height) {
            this.height = Math.max(5, height);
            return this;
        }

        public SimulationConfig build() {
            Objects.requireNonNull(sourceRoot, "sourceRoot must be set");
            Objects.requireNonNull(branch, "branch Optional must not be null");
            Objects.requireNonNull(mapName, "mapName Optional must not be null");
            return new SimulationConfig(this);
        }
    }
}
