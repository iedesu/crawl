package org.develz.crawl.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
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
        private final List<String> rows;

        private MapDefinition(String name, Path source, List<String> rows) {
            this.name = name;
            this.source = source;
            this.rows = rows;
        }

        String name() {
            return name;
        }

        Path source() {
            return source;
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
        char[][] layout = generateBasicLayout(config.width(), config.height(), rng);
        StringBuilder source = new StringBuilder("layout:basic");

        Optional<MapDefinition> vault = chooseMapDefinition(library, config.mapName(),
                config.branch(), rng, allowRandomVaults, config.width(), config.height());
        if (vault.isPresent()) {
            boolean placed = placeVault(layout, vault.get(), rng);
            if (!placed) {
                return new BuildPlan(layout, "DES:" + vault.get().name() + " vetoed (placement)", false);
            }
            source.append(" + DES:").append(vault.get().name());
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
                        maps.addAll(parseDesFile(path));
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

    private List<MapDefinition> parseDesFile(Path desFile) throws IOException {
        List<String> rawLines = Files.readAllLines(desFile, StandardCharsets.UTF_8);
        List<MapDefinition> maps = new ArrayList<>();
        List<String> buffer = null;
        String currentName = null;
        int anonymousCount = 0;

        for (String rawLine : rawLines) {
            String trimmed = rawLine.trim();
            if (trimmed.startsWith("NAME:")) {
                currentName = trimmed.substring("NAME:".length()).trim();
            }
            if (trimmed.startsWith("MAP")) {
                buffer = new ArrayList<>();
                continue;
            }
            if (trimmed.startsWith("ENDMAP")) {
                if (buffer != null) {
                    String name = currentName != null ? currentName : "anonymous-" + (++anonymousCount);
                    maps.add(new MapDefinition(name, desFile, buffer));
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

    private Optional<MapDefinition> chooseMapDefinition(List<MapDefinition> maps,
                                                        Optional<String> desiredName,
                                                        Optional<String> branch,
                                                        Random rng,
                                                        boolean allowRandomVaults,
                                                        int maxWidth,
                                                        int maxHeight) {
        if (maps.isEmpty()) {
            return Optional.empty();
        }

        if (desiredName.isPresent()) {
            String target = desiredName.get().trim();
            Optional<MapDefinition> named = maps.stream()
                    .filter(map -> map.name().equalsIgnoreCase(target))
                    .filter(map -> map.width() <= maxWidth && map.height() <= maxHeight)
                    .findFirst();
            if (named.isPresent()) {
                return named;
            }
        }

        List<MapDefinition> filtered = new ArrayList<>();
        for (MapDefinition map : maps) {
            if (map.width() > maxWidth || map.height() > maxHeight) {
                continue;
            }
            if (!allowRandomVaults && map.name().toLowerCase().contains("vault")) {
                continue;
            }
            if (branch.isPresent() && !map.source().toString().toLowerCase()
                    .contains(branch.get().toLowerCase())) {
                continue;
            }
            filtered.add(map);
        }

        List<MapDefinition> candidates = filtered.isEmpty() ? maps : filtered;
        candidates.sort(Comparator.comparing(MapDefinition::name));
        return Optional.of(candidates.get(rng.nextInt(candidates.size())));
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
