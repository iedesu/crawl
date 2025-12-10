package org.develz.crawl.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Command-line visualizer for {@link DungeonMapSimulator}.
 */
public final class DungeonMapVisualizer {
    public static void main(String[] args) throws IOException {
        Map<String, String> flags = parseArgs(args);
        SimulationConfig config = buildConfig(flags);

        DungeonMapSimulator simulator = new DungeonMapSimulator();
        DungeonMapSimulator.SimulationResult result = simulator.simulate(config);

        System.out.println("Dungeon depth : " + result.depth());
        System.out.println("Seed          : " + result.seed());
        System.out.println("Layout        : " + result.layout());
        System.out.println("Source        : " + result.source());
        System.out.println("Legend        : # wall, . floor, < up stairs, > down stairs, * dlua marker");
        System.out.println();
        System.out.println(simulator.render(result.tiles()));
    }

    private static SimulationConfig buildConfig(Map<String, String> flags) {
        SimulationConfig.Builder builder = SimulationConfig.builder();
        builder.depth(parseInt(flags.getOrDefault("depth", "1"), 1));
        builder.seed(parseLong(flags.getOrDefault("seed", "0"), 0L));
        builder.width(parseInt(flags.getOrDefault("width", "100"), 100));
        builder.height(parseInt(flags.getOrDefault("height", "100"), 100));
        Path sourceRoot = Path.of(flags.getOrDefault("source-root", "crawl-ref/source"));
        builder.sourceRoot(sourceRoot);
        Optional.ofNullable(flags.get("branch"))
                .ifPresent(builder::branch);
        Optional.ofNullable(flags.get("map"))
                .ifPresent(builder::mapName);
        return builder.build();
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> parsed = new HashMap<>();
        for (String arg : args) {
            if (arg.startsWith("--") && arg.contains("=")) {
                int idx = arg.indexOf('=');
                parsed.put(arg.substring(2, idx), arg.substring(idx + 1));
            }
        }
        return parsed;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
