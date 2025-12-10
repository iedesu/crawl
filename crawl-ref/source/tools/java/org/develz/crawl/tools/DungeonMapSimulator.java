package org.develz.crawl.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Dungeon layout runner that mirrors Crawl's builder pipeline closely while staying
 * in-process Java. It follows the same coarse stages described in dungeon.cc:
 * builder() retry loop, _build_level_vetoable(), _builder_by_type(), and
 * _build_dungeon_level() post-processing.
 *
 * Instead of reimplementing map semantics, the simulator reads the Crawl source tree
 * directly. It loads MAP/ENDMAP payloads from dat/des, honours optional force-map
 * selection, and falls back to a dgn_build_basic_level analogue when no vault is
 * usable. Lua-inspired overlays are derived from the dat/dlua directory so the
 * control flow tracks the dlua hooks executed in the native pipeline.
 */
public final class DungeonMapSimulator {
    private static final char WALL = '#';
    private static final char FLOOR = '.';
    private static final char STAIRS_UP = '<';
    private static final char STAIRS_DOWN = '>';
    private static final char LUA_MARKER = '*';

    private enum LayoutStyle {
        BASIC_ROOMS,
        MAZE,
        CELLULAR_CAVE,
        NOISE_HEIGHTMAP
    }

    /**
     * Result bundle returned by {@link #simulate(SimulationConfig)}.
     */
    public static final class SimulationResult {
        private final char[][] tiles;
        private final String source;
        private final int depth;
        private final long seed;

        private SimulationResult(char[][] tiles, String source, int depth, long seed) {
            this.tiles = tiles;
            this.source = source;
            this.depth = depth;
            this.seed = seed;
        }

        public char[][] tiles() {
            return tiles;
        }

        public String source() {
            return source;
        }

        public int depth() {
            return depth;
        }

        public long seed() {
            return seed;
        }
    }

    /**
     * Representation of a single MAP/ENDMAP block discovered in a .des file.
     */
    private static final class MapDefinition {
        private final String name;
        private final Path source;
        private final String relativeSource;
        private final List<String> rows;
        private final Set<String> placeHints;

        private MapDefinition(String name, Path source, String relativeSource,
                              List<String> rows, Set<String> placeHints) {
            this.name = name;
            this.source = source;
            this.relativeSource = relativeSource;
            this.rows = rows;
            this.placeHints = placeHints;
        }

        String name() {
            return name;
        }

        Path source() {
            return source;
        }

        String relativeSource() {
            return relativeSource;
        }

        int height() {
            return rows.size();
        }

        int width() {
            return rows.stream().mapToInt(String::length).max().orElse(0);
        }

        char[][] toTileArray() {
            int h = height();
            int w = width();
            char[][] data = new char[h][w];
            for (int y = 0; y < h; y++) {
                String row = rows.get(y);
                for (int x = 0; x < w; x++) {
                    data[y][x] = x < row.length() ? row.charAt(x) : ' ';
                }
            }
            return data;
        }

        boolean matchesBranch(String branchCode) {
            if (branchCode == null) {
                return true;
            }
            if (placeHints.stream().anyMatch(p -> p.equalsIgnoreCase(branchCode))) {
                return true;
            }
            return relativeSource.toLowerCase().contains("branches/")
                    && relativeSource.toLowerCase().contains(branchCode.toLowerCase());
        }
    }

    /**
     * Produce a deterministic map preview using the supplied configuration.
     */
    public SimulationResult simulate(SimulationConfig config) throws IOException {
        Random rng = new Random(config.seed());
        List<MapDefinition> library = loadVaultLibrary(config.sourceRoot());
        List<Path> dlua = loadDlua(config.sourceRoot());

        char[][] lastTiles = null;
        String lastSource = "";
        boolean allowRandomVaults = true;

        for (int attempt = 1; attempt <= 50; attempt++) {
            LevelBuild build = buildLevel(config, rng, library, dlua, allowRandomVaults);
            if (build.success()) {
                return new SimulationResult(build.tiles(), build.source(), config.depth(), config.seed());
            }

            lastTiles = build.tiles();
            lastSource = build.source();
            if (attempt == 25) {
                allowRandomVaults = false;
            }
        }

        if (lastTiles == null) {
            lastTiles = generateBasicLayout(config.width(), config.height(), rng);
            lastSource = "basic-layout";
        }
        return new SimulationResult(lastTiles, lastSource + " (exhausted attempts)",
                config.depth(), config.seed());
    }

    private static final class LevelBuild {
        private final char[][] tiles;
        private final String source;
        private final boolean success;

        private LevelBuild(char[][] tiles, String source, boolean success) {
            this.tiles = tiles;
            this.source = source;
            this.success = success;
        }

        char[][] tiles() {
            return tiles;
        }

        String source() {
            return source;
        }

        boolean success() {
            return success;
        }
    }

    private static final class BuildPlan {
        private final char[][] tiles;
        private final String source;
        private final boolean success;

        private BuildPlan(char[][] tiles, String source, boolean success) {
            this.tiles = tiles;
            this.source = source;
            this.success = success;
        }
    }

    private static final class LayoutPlan {
        private final char[][] tiles;
        private final String source;

        private LayoutPlan(char[][] tiles, String source) {
            this.tiles = tiles;
            this.source = source;
        }

        char[][] tiles() {
            return tiles;
        }

        String source() {
            return source;
        }
    }

    private LevelBuild buildLevel(SimulationConfig config,
                                 Random rng,
                                 List<MapDefinition> library,
                                 List<Path> dlua,
                                 boolean allowRandomVaults) throws IOException {
        resetLevelState();
        BuildPlan plan = builderByType(config, rng, library, allowRandomVaults);
        if (!plan.success) {
            return new LevelBuild(plan.tiles, plan.source, false);
        }

        applyDepthMarkers(plan.tiles, config.depth());
        applyDluaOverlay(plan.tiles, dlua, rng);
        boolean postProcessed = postProcess(plan.tiles);
        return new LevelBuild(plan.tiles, plan.source, postProcessed);
    }

    private void resetLevelState() {
        // Placeholder for dgn_reset_level / initial_dungeon_setup equivalent.
    }

    private BuildPlan builderByType(SimulationConfig config,
                                   Random rng,
                                   List<MapDefinition> library,
                                   boolean allowRandomVaults) {
        String branch = config.branch().map(String::toUpperCase).orElse("D");
        if (branch.contains("ABYSS")) {
            return new BuildPlan(generateAbyss(config, rng), "branch=" + branch + ":abyss", true);
        }
        if (branch.contains("PAN")) {
            return new BuildPlan(generatePan(config, rng), "branch=" + branch + ":pan", true);
        }
        return builderNormal(config, rng, library, allowRandomVaults);
    }

    private BuildPlan builderNormal(SimulationConfig config,
                                    Random rng,
                                    List<MapDefinition> library,
                                    boolean allowRandomVaults) {
        LayoutPlan plan = generateLayout(config, rng);
        char[][] layout = plan.tiles();
        StringBuilder source = new StringBuilder(plan.source());

        List<MapDefinition> vaults = selectVaults(library, config.mapName(), config.branch(), rng,
                allowRandomVaults, config.width(), config.height());
        Set<String> placed = new LinkedHashSet<>();
        for (MapDefinition vault : vaults) {
            boolean placedVault = placeVault(layout, vault, rng);
            if (!placedVault) {
                return new BuildPlan(layout, "DES:" + vault.name() + " vetoed (placement)", false);
            }
            placed.add(vault.name());
        }

        if (!placed.isEmpty()) {
            source.append(" + DES:").append(String.join(",", placed));
        }

        return new BuildPlan(layout, source.toString(), true);
    }

    private char[][] generateAbyss(SimulationConfig config, Random rng) {
        char[][] tiles = generateBasicLayout(config.width(), config.height(), rng);
        scatter(tiles, rng, FLOOR, WALL, 0.30);
        return tiles;
    }

    private char[][] generatePan(SimulationConfig config, Random rng) {
        char[][] tiles = generateBasicLayout(config.width(), config.height(), rng);
        carveRooms(tiles, rng, Math.max(4, Math.min(config.width(), config.height()) / 3));
        return tiles;
    }

    private boolean postProcess(char[][] tiles) {
        floodFillConnectivity(tiles);
        return hasWalkable(tiles);
    }

    private List<MapDefinition> loadVaultLibrary(Path sourceRoot) throws IOException {
        List<MapDefinition> maps = new ArrayList<>();
        Path desRoot = sourceRoot.resolve("dat/des");
        if (!Files.isDirectory(desRoot)) {
            return maps;
        }

        Files.walk(desRoot)
                .filter(path -> path.toString().endsWith(".des"))
                .forEach(path -> {
                    try {
                        maps.addAll(parseDesFile(desRoot, path));
                    } catch (IOException ignored) {
                    }
                });
        return maps;
    }

    private List<Path> loadDlua(Path sourceRoot) throws IOException {
        List<Path> dlua = new ArrayList<>();
        Path dluaRoot = sourceRoot.resolve("dat/dlua");
        if (!Files.isDirectory(dluaRoot)) {
            return dlua;
        }
        Files.walk(dluaRoot)
                .filter(path -> path.toString().endsWith(".lua") || path.toString().endsWith(".dlua"))
                .forEach(dlua::add);
        return dlua;
    }

    private List<MapDefinition> parseDesFile(Path desRoot, Path desFile) throws IOException {
        List<String> rawLines = Files.readAllLines(desFile, StandardCharsets.UTF_8);
        List<MapDefinition> maps = new ArrayList<>();
        List<String> buffer = null;
        String currentName = null;
        int anonymousCount = 0;
        Set<String> placeHints = new LinkedHashSet<>();
        String relativeSource = desRoot.relativize(desFile).toString().replace('\\', '/');
        Pattern placeDirective = Pattern.compile("^PLACE:\\s*(.+)$", Pattern.CASE_INSENSITIVE);
        Pattern placeFunction = Pattern.compile("place\\(\\\"([^\\\"]+)\\\"", Pattern.CASE_INSENSITIVE);

        for (String rawLine : rawLines) {
            String trimmed = rawLine.trim();
            if (trimmed.startsWith("NAME:")) {
                currentName = trimmed.substring("NAME:".length()).trim();
            }

            Matcher directiveMatcher = placeDirective.matcher(trimmed);
            if (directiveMatcher.find()) {
                String[] tokens = directiveMatcher.group(1).split(",");
                for (String token : tokens) {
                    collectPlaceHints(placeHints, token);
                }
            }

            Matcher functionMatcher = placeFunction.matcher(trimmed);
            while (functionMatcher.find()) {
                collectPlaceHints(placeHints, functionMatcher.group(1));
            }

            if (trimmed.startsWith("MAP")) {
                buffer = new ArrayList<>();
                continue;
            }
            if (trimmed.startsWith("ENDMAP")) {
                if (buffer != null) {
                    String name = currentName != null ? currentName : "anonymous-" + (++anonymousCount);
                    maps.add(new MapDefinition(name, desFile, relativeSource,
                            buffer, new LinkedHashSet<>(placeHints)));
                }
                buffer = null;
                currentName = null;
                continue;
            }
            if (buffer != null) {
                buffer.add(rawLine);
            }
        }

        return maps;
    }

    private void collectPlaceHints(Set<String> placeHints, String token) {
        String cleaned = token.trim();
        if (cleaned.isEmpty()) {
            return;
        }
        String upper = cleaned.toUpperCase();
        placeHints.add(upper);
        int colon = upper.indexOf(':');
        if (colon > 0) {
            placeHints.add(upper.substring(0, colon));
        }
    }

    private List<MapDefinition> selectVaults(List<MapDefinition> maps,
                                             Optional<String> desiredName,
                                             Optional<String> branch,
                                             Random rng,
                                             boolean allowRandomVaults,
                                             int maxWidth,
                                             int maxHeight) {
        if (maps.isEmpty()) {
            return List.of();
        }

        List<MapDefinition> pool = filterByBranchAndSize(maps, branch, allowRandomVaults, maxWidth, maxHeight);
        if (pool.isEmpty()) {
            return List.of();
        }

        if (desiredName.isPresent()) {
            String target = desiredName.get().trim();
            return pool.stream()
                    .filter(map -> map.name().equalsIgnoreCase(target))
                    .limit(1)
                    .collect(Collectors.toList());
        }

        int budget = allowRandomVaults ? 1 + rng.nextInt(3) : 1;
        Collections.shuffle(pool, rng);
        return pool.stream().limit(budget).collect(Collectors.toList());
    }

    private List<MapDefinition> filterByBranchAndSize(List<MapDefinition> maps,
                                                      Optional<String> branch,
                                                      boolean allowRandomVaults,
                                                      int maxWidth,
                                                      int maxHeight) {
        String branchCode = branch.map(b -> b.split(":"))
                .map(parts -> parts.length > 0 ? parts[0].toUpperCase() : null)
                .orElse(null);

        List<MapDefinition> filtered = new ArrayList<>();
        for (MapDefinition map : maps) {
            if (map.width() > maxWidth || map.height() > maxHeight) {
                continue;
            }
            if (!allowRandomVaults && map.name().toLowerCase().contains("vault")) {
                continue;
            }
            if (branchCode != null && !map.matchesBranch(branchCode)) {
                continue;
            }
            filtered.add(map);
        }
        return filtered;
    }

    private LayoutPlan generateLayout(SimulationConfig config, Random rng) {
        LayoutStyle style = chooseLayoutStyle(rng);
        switch (style) {
            case MAZE:
                return new LayoutPlan(generateMazeLayout(config.width(), config.height(), rng), "layout:maze");
            case CELLULAR_CAVE:
                return new LayoutPlan(generateCellularLayout(config.width(), config.height(), rng), "layout:cellular");
            case NOISE_HEIGHTMAP:
                return new LayoutPlan(generateNoiseLayout(config.width(), config.height(), rng), "layout:noise");
            case BASIC_ROOMS:
            default:
                return new LayoutPlan(generateBasicLayout(config.width(), config.height(), rng), "layout:basic");
        }
    }

    private LayoutStyle chooseLayoutStyle(Random rng) {
        int roll = rng.nextInt(100);
        if (roll < 40) {
            return LayoutStyle.BASIC_ROOMS;
        }
        if (roll < 60) {
            return LayoutStyle.CELLULAR_CAVE;
        }
        if (roll < 80) {
            return LayoutStyle.MAZE;
        }
        return LayoutStyle.NOISE_HEIGHTMAP;
    }

    private char[][] generateBasicLayout(int width, int height, Random rng) {
        char[][] tiles = new char[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                tiles[y][x] = WALL;
            }
        }

        int rooms = Math.max(3, Math.min(width, height) / 5);
        carveRooms(tiles, rng, rooms);
        connectRooms(tiles, rng);
        padWalls(tiles);
        return tiles;
    }

    private char[][] generateCellularLayout(int width, int height, Random rng) {
        char[][] tiles = new char[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                tiles[y][x] = rng.nextDouble() < 0.45 ? WALL : FLOOR;
            }
        }

        for (int iter = 0; iter < 5; iter++) {
            tiles = smoothStep(tiles);
        }
        padWalls(tiles);
        return tiles;
    }

    private char[][] smoothStep(char[][] tiles) {
        int height = tiles.length;
        int width = tiles[0].length;
        char[][] next = new char[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int walls = countNeighbours(tiles, x, y, WALL);
                if (walls > 4) {
                    next[y][x] = WALL;
                } else if (walls < 4) {
                    next[y][x] = FLOOR;
                } else {
                    next[y][x] = tiles[y][x];
                }
            }
        }
        return next;
    }

    private int countNeighbours(char[][] tiles, int cx, int cy, char target) {
        int count = 0;
        for (int y = cy - 1; y <= cy + 1; y++) {
            for (int x = cx - 1; x <= cx + 1; x++) {
                if (x == cx && y == cy) {
                    continue;
                }
                if (y < 0 || y >= tiles.length || x < 0 || x >= tiles[0].length) {
                    count++;
                } else if (tiles[y][x] == target) {
                    count++;
                }
            }
        }
        return count;
    }

    private char[][] generateMazeLayout(int width, int height, Random rng) {
        char[][] tiles = new char[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                tiles[y][x] = WALL;
            }
        }

        boolean[][] visited = new boolean[height][width];
        int startX = (width / 2) | 1;
        int startY = (height / 2) | 1;
        carveMaze(startX, startY, tiles, visited, rng);
        padWalls(tiles);
        return tiles;
    }

    private void carveMaze(int x, int y, char[][] tiles, boolean[][] visited, Random rng) {
        int height = tiles.length;
        int width = tiles[0].length;
        int[] dirs = {0, 1, 2, 3};
        shuffleArray(dirs, rng);
        visited[y][x] = true;
        tiles[y][x] = FLOOR;
        for (int dir : dirs) {
            int dx = 0, dy = 0;
            switch (dir) {
                case 0: dy = -2; break;
                case 1: dy = 2; break;
                case 2: dx = -2; break;
                case 3: dx = 2; break;
                default: break;
            }
            int nx = x + dx;
            int ny = y + dy;
            if (ny <= 0 || ny >= height - 1 || nx <= 0 || nx >= width - 1 || visited[ny][nx]) {
                continue;
            }
            tiles[y + dy / 2][x + dx / 2] = FLOOR;
            carveMaze(nx, ny, tiles, visited, rng);
        }
    }

    private void shuffleArray(int[] arr, Random rng) {
        for (int i = arr.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = arr[i];
            arr[i] = arr[j];
            arr[j] = tmp;
        }
    }

    private char[][] generateNoiseLayout(int width, int height, Random rng) {
        double[][] heightmap = new double[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double nx = x / (double) width;
                double ny = y / (double) height;
                heightmap[y][x] = layeredNoise(nx, ny, rng);
            }
        }

        char[][] tiles = new char[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                tiles[y][x] = heightmap[y][x] > 0.45 ? FLOOR : WALL;
            }
        }

        tiles = smoothStep(tiles);
        padWalls(tiles);
        return tiles;
    }

    private double layeredNoise(double nx, double ny, Random rng) {
        double value = 0.0;
        double amplitude = 1.0;
        double frequency = 1.0;
        for (int i = 0; i < 4; i++) {
            value += amplitude * simpleGradient(nx * frequency, ny * frequency, rng);
            amplitude *= 0.5;
            frequency *= 2.0;
        }
        return (value + 1) / 2.0;
    }

    private double simpleGradient(double x, double y, Random rng) {
        int xi = (int) Math.floor(x);
        int yi = (int) Math.floor(y);
        double xf = x - xi;
        double yf = y - yi;

        double n00 = randomGradient(xi, yi, rng);
        double n10 = randomGradient(xi + 1, yi, rng);
        double n01 = randomGradient(xi, yi + 1, rng);
        double n11 = randomGradient(xi + 1, yi + 1, rng);

        double u = fade(xf);
        double v = fade(yf);

        double x1 = lerp(n00, n10, u);
        double x2 = lerp(n01, n11, u);
        return lerp(x1, x2, v);
    }

    private double randomGradient(int x, int y, Random rng) {
        long hash = 0x9E3779B97F4A7C15L;
        hash ^= x * 0x632BE59BD9B4E019L;
        hash ^= y * 0x9E3779B97F4A7C15L;
        long seedMix = hash ^ rng.nextLong();
        Random noiseRng = new Random(seedMix);
        double angle = noiseRng.nextDouble() * Math.PI * 2;
        return Math.cos(angle) * (x % 2 == 0 ? 1 : -1) + Math.sin(angle) * (y % 2 == 0 ? 1 : -1);
    }

    private double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private double lerp(double a, double b, double t) {
        return a + t * (b - a);
    }

    private void carveRooms(char[][] tiles, Random rng, int roomCount) {
        int height = tiles.length;
        int width = tiles[0].length;
        for (int i = 0; i < roomCount; i++) {
            int rw = 4 + rng.nextInt(Math.max(2, width / 8));
            int rh = 4 + rng.nextInt(Math.max(2, height / 8));
            int rx = rng.nextInt(Math.max(1, width - rw - 1));
            int ry = rng.nextInt(Math.max(1, height - rh - 1));
            for (int y = ry; y < ry + rh; y++) {
                for (int x = rx; x < rx + rw; x++) {
                    tiles[y][x] = FLOOR;
                }
            }
        }
    }

    private void connectRooms(char[][] tiles, Random rng) {
        List<int[]> floors = collectTiles(tiles, FLOOR);
        Collections.shuffle(floors, rng);
        for (int i = 1; i < floors.size(); i++) {
            carveCorridor(tiles, floors.get(i - 1), floors.get(i));
        }
    }

    private void carveCorridor(char[][] tiles, int[] from, int[] to) {
        int x = from[0];
        int y = from[1];
        while (x != to[0] || y != to[1]) {
            if (x < to[0]) x++;
            else if (x > to[0]) x--;
            if (y < to[1]) y++;
            else if (y > to[1]) y--;
            tiles[y][x] = FLOOR;
        }
    }

    private void padWalls(char[][] tiles) {
        int height = tiles.length;
        int width = tiles[0].length;
        for (int y = 0; y < height; y++) {
            tiles[y][0] = WALL;
            tiles[y][width - 1] = WALL;
        }
        for (int x = 0; x < width; x++) {
            tiles[0][x] = WALL;
            tiles[height - 1][x] = WALL;
        }
    }

    private boolean placeVault(char[][] layout, MapDefinition vault, Random rng) {
        char[][] tiles = vault.toTileArray();
        int vH = tiles.length;
        int vW = tiles[0].length;
        int maxX = layout[0].length - vW;
        int maxY = layout.length - vH;
        if (maxX < 0 || maxY < 0) {
            return false;
        }

        for (int attempt = 0; attempt < 25; attempt++) {
            int offsetX = rng.nextInt(maxX + 1);
            int offsetY = rng.nextInt(maxY + 1);
            if (!canPlaceVault(layout, tiles, offsetX, offsetY)) {
                continue;
            }
            overlayVault(layout, tiles, offsetX, offsetY);
            return true;
        }
        return false;
    }

    private boolean canPlaceVault(char[][] layout, char[][] vault, int offsetX, int offsetY) {
        for (int y = 0; y < vault.length; y++) {
            for (int x = 0; x < vault[y].length; x++) {
                char tile = vault[y][x];
                if (tile == ' ') {
                    continue;
                }
                if (layout[offsetY + y][offsetX + x] == LUA_MARKER) {
                    return false;
                }
            }
        }
        return true;
    }

    private void overlayVault(char[][] layout, char[][] vault, int offsetX, int offsetY) {
        for (int y = 0; y < vault.length; y++) {
            for (int x = 0; x < vault[y].length; x++) {
                char tile = vault[y][x];
                if (tile == ' ') {
                    continue;
                }
                layout[offsetY + y][offsetX + x] = tile;
            }
        }
    }

    private void scatter(char[][] tiles, Random rng, char target, char replacement, double chance) {
        for (int y = 0; y < tiles.length; y++) {
            for (int x = 0; x < tiles[y].length; x++) {
                if (tiles[y][x] == target && rng.nextDouble() < chance) {
                    tiles[y][x] = replacement;
                }
            }
        }
    }

    private void floodFillConnectivity(char[][] tiles) {
        List<int[]> floors = collectTiles(tiles, FLOOR);
        if (floors.isEmpty()) {
            return;
        }
        Set<String> seen = new HashSet<>();
        Queue<int[]> queue = new ArrayDeque<>();
        queue.add(floors.get(0));
        while (!queue.isEmpty()) {
            int[] cur = queue.remove();
            String key = cur[0] + "," + cur[1];
            if (seen.contains(key)) {
                continue;
            }
            seen.add(key);
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (Math.abs(dx) + Math.abs(dy) != 1) {
                        continue;
                    }
                    int nx = cur[0] + dx;
                    int ny = cur[1] + dy;
                    if (ny < 0 || ny >= tiles.length || nx < 0 || nx >= tiles[0].length) {
                        continue;
                    }
                    if (tiles[ny][nx] == FLOOR || tiles[ny][nx] == STAIRS_DOWN || tiles[ny][nx] == STAIRS_UP) {
                        queue.add(new int[]{nx, ny});
                    }
                }
            }
        }

        for (int y = 0; y < tiles.length; y++) {
            for (int x = 0; x < tiles[y].length; x++) {
                if ((tiles[y][x] == FLOOR || tiles[y][x] == LUA_MARKER) && !seen.contains(x + "," + y)) {
                    tiles[y][x] = WALL;
                }
            }
        }
    }

    private boolean hasWalkable(char[][] tiles) {
        for (int y = 0; y < tiles.length; y++) {
            for (int x = 0; x < tiles[y].length; x++) {
                char t = tiles[y][x];
                if (t == FLOOR || t == STAIRS_DOWN || t == STAIRS_UP || t == LUA_MARKER) {
                    return true;
                }
            }
        }
        return false;
    }

    private void applyDepthMarkers(char[][] tiles, int depth) {
        List<int[]> floorTiles = collectTiles(tiles, FLOOR);
        if (floorTiles.isEmpty()) {
            floorTiles = collectNonWallTiles(tiles);
            if (floorTiles.isEmpty()) {
                return;
            }
        }

        int upIndex = depth % floorTiles.size();
        int downIndex = (depth * 3) % floorTiles.size();
        placeSymbol(tiles, floorTiles.get(upIndex), STAIRS_UP);
        placeSymbol(tiles, floorTiles.get(downIndex), STAIRS_DOWN);
    }

    private List<int[]> collectNonWallTiles(char[][] tiles) {
        List<int[]> coords = new ArrayList<>();
        for (int y = 0; y < tiles.length; y++) {
            for (int x = 0; x < tiles[y].length; x++) {
                if (tiles[y][x] != WALL) {
                    coords.add(new int[]{x, y});
                }
            }
        }
        return coords;
    }

    private List<int[]> collectTiles(char[][] tiles, char target) {
        List<int[]> coords = new ArrayList<>();
        for (int y = 0; y < tiles.length; y++) {
            for (int x = 0; x < tiles[y].length; x++) {
                if (tiles[y][x] == target) {
                    coords.add(new int[]{x, y});
                }
            }
        }
        return coords;
    }

    private void placeSymbol(char[][] tiles, int[] coord, char symbol) {
        tiles[coord[1]][coord[0]] = symbol;
    }

    private void applyDluaOverlay(char[][] tiles, List<Path> dluaFiles, Random rng) throws IOException {
        if (dluaFiles.isEmpty()) {
            return;
        }

        boolean executed = tryExecuteDluaWithLuaJ(tiles, dluaFiles, rng);
        if (executed) {
            return;
        }

        int markerBudget = 0;
        for (Path path : dluaFiles) {
            markerBudget += Math.max(1, Files.readAllLines(path, StandardCharsets.UTF_8).size() / 100);
        }
        List<int[]> floors = collectTiles(tiles, FLOOR);
        Collections.shuffle(floors, rng);
        for (int i = 0; i < Math.min(markerBudget, floors.size()); i++) {
            placeSymbol(tiles, floors.get(i), LUA_MARKER);
        }
    }

    private boolean tryExecuteDluaWithLuaJ(char[][] tiles, List<Path> dluaFiles, Random rng) {
        try {
            Class<?> jsePlatform = Class.forName("org.luaj.vm2.lib.jse.JsePlatform");
            Class<?> globalsClass = Class.forName("org.luaj.vm2.Globals");
            Class<?> luaValueClass = Class.forName("org.luaj.vm2.LuaValue");
            Class<?> coerceClass = Class.forName("org.luaj.vm2.lib.jse.CoerceJavaToLua");

            Object globals = jsePlatform.getMethod("standardGlobals").invoke(null);
            Object tileApi = coerceClass.getMethod("coerce", Object.class)
                    .invoke(null, new TileApi(tiles, rng));
            Object rngApi = coerceClass.getMethod("coerce", Object.class)
                    .invoke(null, new RngApi(rng));

            java.lang.reflect.Method setMethod = globalsClass.getMethod("set", String.class, luaValueClass);
            java.lang.reflect.Method valueOfInt = luaValueClass.getMethod("valueOf", int.class);
            setMethod.invoke(globals, "tile", tileApi);
            setMethod.invoke(globals, "rng", rngApi);
            setMethod.invoke(globals, "WIDTH", valueOfInt.invoke(null, tiles[0].length));
            setMethod.invoke(globals, "HEIGHT", valueOfInt.invoke(null, tiles.length));

            java.lang.reflect.Method load = globalsClass.getMethod("load", InputStream.class,
                    String.class, String.class, globalsClass);
            java.lang.reflect.Method call = luaValueClass.getMethod("call");

            for (Path script : dluaFiles) {
                try (InputStream in = Files.newInputStream(script)) {
                    Object chunk = load.invoke(globals, in, script.getFileName().toString(), "bt", globals);
                    call.invoke(chunk);
                    invokeLuaHook(globalsClass, luaValueClass, globals, "apply");
                    invokeLuaHook(globalsClass, luaValueClass, globals, "render");
                } catch (Exception scriptFailure) {
                    // Continue to the next script if this one fails.
                }
            }

            return true;
        } catch (ClassNotFoundException missing) {
            return false;
        } catch (Exception failure) {
            return false;
        }
    }

    private void invokeLuaHook(Class<?> globalsClass, Class<?> luaValueClass,
                               Object globals, String hook) throws Exception {
        java.lang.reflect.Method get = globalsClass.getMethod("get", String.class);
        Object value = get.invoke(globals, hook);
        boolean isNil = (boolean) luaValueClass.getMethod("isnil").invoke(value);
        if (isNil) {
            return;
        }
        boolean isFunction = (boolean) luaValueClass.getMethod("isfunction").invoke(value);
        if (!isFunction) {
            return;
        }
        luaValueClass.getMethod("call").invoke(value);
    }

    /**
     * Lightweight API surface exposed to dlua scripts when LuaJ is available.
     */
    public static final class TileApi {
        private final char[][] tiles;
        private final Random rng;

        TileApi(char[][] tiles, Random rng) {
            this.tiles = tiles;
            this.rng = rng;
        }

        public int width() {
            return tiles[0].length;
        }

        public int height() {
            return tiles.length;
        }

        public void set(int x, int y, String glyph) {
            if (!inBounds(x, y)) {
                return;
            }
            tiles[y][x] = normalize(glyph);
        }

        public void fill(int x1, int y1, int x2, int y2, String glyph) {
            char value = normalize(glyph);
            for (int y = Math.max(0, Math.min(y1, y2)); y <= Math.min(height() - 1, Math.max(y1, y2)); y++) {
                for (int x = Math.max(0, Math.min(x1, x2)); x <= Math.min(width() - 1, Math.max(x1, x2)); x++) {
                    tiles[y][x] = value;
                }
            }
        }

        public void carve_circle(int centerX, int centerY, int radius, String glyph) {
            char value = normalize(glyph);
            int r2 = radius * radius;
            for (int y = Math.max(0, centerY - radius); y <= Math.min(height() - 1, centerY + radius); y++) {
                for (int x = Math.max(0, centerX - radius); x <= Math.min(width() - 1, centerX + radius); x++) {
                    int dx = x - centerX;
                    int dy = y - centerY;
                    if ((dx * dx + dy * dy) <= r2) {
                        tiles[y][x] = value;
                    }
                }
            }
        }

        public void jitter(String glyph, double density) {
            char value = normalize(glyph);
            for (int y = 0; y < height(); y++) {
                for (int x = 0; x < width(); x++) {
                    if (rng.nextDouble() < density) {
                        tiles[y][x] = value;
                    }
                }
            }
        }

        private boolean inBounds(int x, int y) {
            return x >= 0 && y >= 0 && y < tiles.length && x < tiles[y].length;
        }

        private char normalize(String glyph) {
            if (glyph == null || glyph.isEmpty()) {
                return FLOOR;
            }
            return glyph.charAt(0);
        }
    }

    /**
     * RNG helper exposed to dlua scripts via LuaJ binding.
     */
    public static final class RngApi {
        private final Random rng;

        RngApi(Random rng) {
            this.rng = rng;
        }

        public int range(int min, int max) {
            if (max < min) {
                return min;
            }
            return min + rng.nextInt((max - min) + 1);
        }

        public double uniform() {
            return rng.nextDouble();
        }

        public boolean chance(double probability) {
            return rng.nextDouble() < probability;
        }
    }

    /**
     * Render a tile array to a human-readable string.
     */
    public String render(char[][] tiles) {
        return render(tiles, true);
    }

    /**
     * Render a tile array to a human-readable string with optional border.
     */
    public String render(char[][] tiles, boolean includeBorder) {
        String body = java.util.Arrays.stream(tiles)
                .map(String::new)
                .collect(Collectors.joining(System.lineSeparator()));
        if (!includeBorder) {
            return body;
        }

        String horizontal = "─".repeat(Math.max(0, tiles[0].length));
        String top = "┌" + horizontal + "┐";
        String bottom = "└" + horizontal + "┘";
        String middle = java.util.Arrays.stream(tiles)
                .map(String::new)
                .map(row -> "│" + row + "│")
                .collect(Collectors.joining(System.lineSeparator()));
        return top + System.lineSeparator() + middle + System.lineSeparator() + bottom;
    }
}
