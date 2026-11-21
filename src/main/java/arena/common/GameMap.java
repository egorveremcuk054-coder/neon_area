package arena.common;

import java.util.ArrayList;
import java.util.List;

/**
 * Карта арены — сетка тайлов 40×40 пикселей.
 * Символы: '#' стена, '.' пол, 'S' точка появления,
 * 'H' аптечка, 'R' скорострельность, 'V' ускорение.
 */
public final class GameMap {
    public static final int TILE = 40;
    private static final double EPS = 1e-6;

    private static final String[] DEFAULT_MAP = {
            "################################",
            "#..............................#",
            "#.S..........................S.#",
            "#.....####............####.....#",
            "#.....#..................#.....#",
            "#..............................#",
            "#.............####.............#",
            "#..H......#..........#.........#",
            "#....#....#....R.....#....#....#",
            "#....#....#.....V....#....#....#",
            "#.........#..........#......H..#",
            "#.............####.............#",
            "#..............................#",
            "#.....#..................#.....#",
            "#.....####............####.....#",
            "#.S..........................S.#",
            "#..............................#",
            "################################",
    };

    public record Spot(double x, double y) {}

    public record PickupSpot(PickupType type, double x, double y) {}

    private final List<String> rows;
    private final int width;   // в тайлах
    private final int height;  // в тайлах
    private final boolean[][] walls;
    private final List<Spot> spawns = new ArrayList<>();
    private final List<PickupSpot> pickupSpots = new ArrayList<>();

    public GameMap(List<String> rows) {
        if (rows.isEmpty()) throw new IllegalArgumentException("Пустая карта");
        this.rows = List.copyOf(rows);
        this.height = rows.size();
        this.width = rows.get(0).length();
        this.walls = new boolean[height][width];

        for (int y = 0; y < height; y++) {
            String row = rows.get(y);
            if (row.length() != width) {
                throw new IllegalArgumentException(
                        "Строка " + y + " карты длиной " + row.length() + ", а должна быть " + width);
            }
            for (int x = 0; x < width; x++) {
                double centerX = (x + 0.5) * TILE;
                double centerY = (y + 0.5) * TILE;
                switch (row.charAt(x)) {
                    case '#' -> walls[y][x] = true;
                    case 'S' -> spawns.add(new Spot(centerX, centerY));
                    case 'H' -> pickupSpots.add(new PickupSpot(PickupType.HEALTH, centerX, centerY));
                    case 'R' -> pickupSpots.add(new PickupSpot(PickupType.RAPID_FIRE, centerX, centerY));
                    case 'V' -> pickupSpots.add(new PickupSpot(PickupType.SPEED, centerX, centerY));
                    case '.' -> { }
                    default -> throw new IllegalArgumentException("Неизвестный символ '" + row.charAt(x) + "' в карте");
                }
            }
        }
        if (spawns.isEmpty()) throw new IllegalArgumentException("На карте нет точек появления 'S'");
    }

    public static GameMap createDefault() {
        return new GameMap(List.of(DEFAULT_MAP));
    }

    public List<String> rows() { return rows; }
    public int width() { return width; }
    public int height() { return height; }
    public int pixelWidth() { return width * TILE; }
    public int pixelHeight() { return height * TILE; }
    public List<Spot> spawns() { return spawns; }
    public List<PickupSpot> pickupSpots() { return pickupSpots; }

    public static int tileOf(double pixel) {
        return (int) Math.floor(pixel / TILE);
    }

    /** Всё, что за краем карты, тоже считается стеной. */
    public boolean isWall(int tileX, int tileY) {
        if (tileX < 0 || tileY < 0 || tileX >= width || tileY >= height) return true;
        return walls[tileY][tileX];
    }

    public boolean isWallAt(double x, double y) {
        return isWall(tileOf(x), tileOf(y));
    }

    /**
     * Сдвигает квадрат с полуразмером r по оси X. Если впереди стена — упирается в неё.
     * Движение по X и Y считается отдельно: так игрок «скользит» вдоль стен, а не застревает.
     */
    public double moveX(double x, double y, double dx, double r) {
        if (dx == 0) return x;
        double newX = x + dx;
        int top = tileOf(y - r);
        int bottom = tileOf(y + r - EPS);
        if (dx > 0) {
            int column = tileOf(newX + r - EPS);
            for (int row = top; row <= bottom; row++) {
                if (isWall(column, row)) return column * TILE - r;
            }
        } else {
            int column = tileOf(newX - r);
            for (int row = top; row <= bottom; row++) {
                if (isWall(column, row)) return (column + 1) * TILE + r;
            }
        }
        return newX;
    }

    /** То же самое по оси Y. */
    public double moveY(double x, double y, double dy, double r) {
        if (dy == 0) return y;
        double newY = y + dy;
        int left = tileOf(x - r);
        int right = tileOf(x + r - EPS);
        if (dy > 0) {
            int row = tileOf(newY + r - EPS);
            for (int column = left; column <= right; column++) {
                if (isWall(column, row)) return row * TILE - r;
            }
        } else {
            int row = tileOf(newY - r);
            for (int column = left; column <= right; column++) {
                if (isWall(column, row)) return (row + 1) * TILE + r;
            }
        }
        return newY;
    }

    /** Видно ли одну точку из другой: идём по отрезку маленькими шагами и проверяем стены. */
    public boolean hasLineOfSight(double x1, double y1, double x2, double y2) {
        double distance = Math.hypot(x2 - x1, y2 - y1);
        int steps = (int) Math.ceil(distance / 8);
        for (int i = 1; i < steps; i++) {
            double t = i / (double) steps;
            if (isWallAt(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t)) return false;
        }
        return true;
    }
}
