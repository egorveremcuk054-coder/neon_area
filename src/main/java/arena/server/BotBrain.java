package arena.server;

import arena.common.GameMap;
import arena.common.PickupType;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Random;

import static arena.common.GameMap.TILE;
import static arena.common.Message.Input.*;

/**
 * Логика ботов. Каждый тик решает, куда идти и куда стрелять,
 * и «нажимает» те же кнопки, что и человек. Поэтому для сервера бот ничем не отличается от игрока.
 */
final class BotBrain {
    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final double SHOOT_DISTANCE = 650;
    private static final double STRAFE_DISTANCE = 280;
    private static final int LOW_HP = 40;

    private final GameMap map;
    private final Random random;
    private int[] waypoint; // следующий тайл на пути к цели
    private double repathTimer;
    private double strafeTimer;
    private int strafeDirection = 1;
    private double aimError;
    private double aimErrorTimer;
    private double reactionTimer;

    BotBrain(GameMap map, Random random) {
        this.map = map;
        this.random = random;
    }

    void think(ServerPlayer me, World world, double dt) {
        me.buttons = 0;
        if (!me.alive) {
            waypoint = null;
            return;
        }

        ServerPlayer enemy = nearestEnemy(me, world);
        double enemyDistance = enemy == null ? Double.MAX_VALUE
                : Math.max(1e-6, Math.hypot(enemy.x - me.x, enemy.y - me.y));
        boolean seesEnemy = enemy != null && map.hasLineOfSight(me.x, me.y, enemy.x, enemy.y);

        // 1. Выбираем цель: раненый бот бежит за аптечкой, иначе — к врагу, иначе — к бонусу.
        Pickup medkit = me.hp <= LOW_HP ? nearestPickup(me, world, PickupType.HEALTH) : null;
        double goalX;
        double goalY;
        if (medkit != null) {
            goalX = medkit.x;
            goalY = medkit.y;
        } else if (enemy != null) {
            goalX = enemy.x;
            goalY = enemy.y;
        } else {
            Pickup any = nearestPickup(me, world, null);
            goalX = any != null ? any.x : map.pixelWidth() / 2.0;
            goalY = any != null ? any.y : map.pixelHeight() / 2.0;
        }

        // 2. Двигаемся.
        double moveX = 0;
        double moveY = 0;
        if (medkit == null && seesEnemy && enemyDistance < STRAFE_DISTANCE) {
            // Враг рядом — кружим вокруг него, так в бота сложнее попасть.
            strafeTimer -= dt;
            if (strafeTimer <= 0) {
                strafeTimer = 0.5 + random.nextDouble();
                strafeDirection = -strafeDirection;
            }
            double nx = (enemy.x - me.x) / enemyDistance;
            double ny = (enemy.y - me.y) / enemyDistance;
            moveX = -ny * strafeDirection - nx * 0.4;
            moveY = nx * strafeDirection - ny * 0.4;
            press(me, moveX, moveY, 0.3);
        } else {
            repathTimer -= dt;
            if (repathTimer <= 0 || waypoint == null || isInside(me, waypoint)) {
                repathTimer = 0.25;
                waypoint = nextStep(me.x, me.y, goalX, goalY);
            }
            if (waypoint != null) {
                moveX = (waypoint[0] + 0.5) * TILE - me.x;
                moveY = (waypoint[1] + 0.5) * TILE - me.y;
                press(me, moveX, moveY, 2);
            }
        }

        // 3. Целимся и стреляем. Бот немного мажет и реагирует не мгновенно — иначе играть с ним неинтересно.
        aimErrorTimer -= dt;
        if (aimErrorTimer <= 0) {
            aimErrorTimer = 0.4;
            aimError = (random.nextDouble() - 0.5) * 0.3;
        }
        if (seesEnemy) {
            reactionTimer -= dt;
            me.aim = Math.atan2(enemy.y - me.y, enemy.x - me.x) + aimError;
            if (reactionTimer <= 0 && enemyDistance < SHOOT_DISTANCE) me.buttons |= FIRE;
        } else {
            reactionTimer = 0.35;
            if (moveX != 0 || moveY != 0) me.aim = Math.atan2(moveY, moveX);
        }
    }

    private static void press(ServerPlayer me, double dx, double dy, double threshold) {
        if (dx > threshold) me.buttons |= RIGHT;
        else if (dx < -threshold) me.buttons |= LEFT;
        if (dy > threshold) me.buttons |= DOWN;
        else if (dy < -threshold) me.buttons |= UP;
    }

    private static boolean isInside(ServerPlayer me, int[] tile) {
        return GameMap.tileOf(me.x) == tile[0] && GameMap.tileOf(me.y) == tile[1];
    }

    /**
     * Поиск в ширину (BFS) по сетке тайлов: находим кратчайший путь до цели
     * и возвращаем только первый шаг. Карта маленькая (32×18), поэтому это очень быстро.
     */
    private int[] nextStep(double fromX, double fromY, double toX, double toY) {
        int width = map.width();
        int startX = GameMap.tileOf(fromX), startY = GameMap.tileOf(fromY);
        int goalX = GameMap.tileOf(toX), goalY = GameMap.tileOf(toY);
        if (startX == goalX && startY == goalY) return new int[]{goalX, goalY};

        int start = startY * width + startX;
        int goal = goalY * width + goalX;
        int[] previous = new int[width * map.height()];
        Arrays.fill(previous, -1);
        previous[start] = start;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(start);

        while (!queue.isEmpty()) {
            int current = queue.poll();
            if (current == goal) break;
            int cx = current % width, cy = current / width;
            for (int[] d : DIRECTIONS) {
                int nx = cx + d[0], ny = cy + d[1];
                if (map.isWall(nx, ny)) continue;
                int next = ny * width + nx;
                if (previous[next] != -1) continue;
                previous[next] = current;
                queue.add(next);
            }
        }
        if (previous[goal] == -1) return null; // цель недостижима

        // Идём от цели назад по цепочке «откуда пришли», пока не дойдём до первого шага.
        int step = goal;
        while (previous[step] != start) step = previous[step];
        return new int[]{step % width, step / width};
    }

    private static ServerPlayer nearestEnemy(ServerPlayer me, World world) {
        ServerPlayer best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ServerPlayer p : world.players()) {
            if (p == me || !p.alive) continue;
            double d = Math.hypot(p.x - me.x, p.y - me.y);
            if (d < bestDistance) {
                bestDistance = d;
                best = p;
            }
        }
        return best;
    }

    /** type == null означает «любой бонус». */
    private static Pickup nearestPickup(ServerPlayer me, World world, PickupType type) {
        Pickup best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Pickup k : world.pickups()) {
            if (!k.active || (type != null && k.type != type)) continue;
            double d = Math.hypot(k.x - me.x, k.y - me.y);
            if (d < bestDistance) {
                bestDistance = d;
                best = k;
            }
        }
        return best;
    }
}
