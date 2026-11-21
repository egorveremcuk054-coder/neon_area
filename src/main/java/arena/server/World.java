package arena.server;

import arena.common.GameMap;
import arena.common.Message;
import arena.common.PickupType;
import arena.common.Snapshot;
import arena.common.Snapshot.BulletState;
import arena.common.Snapshot.PickupState;
import arena.common.Snapshot.PlayerState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static arena.common.Config.*;
import static arena.common.Message.Input.*;

/**
 * Игровой мир. Только сервер решает, кто куда сдвинулся, кто в кого попал и кто погиб, —
 * клиенты лишь присылают нажатые кнопки. Так гораздо сложнее жульничать.
 * Все методы вызываются под общей блокировкой из GameServer, поэтому синхронизация здесь не нужна.
 */
public final class World {
    private static final String[] BOT_NAMES = {"Бот Гоша", "Бот Нюра", "Бот Жора", "Бот Зина"};

    private final GameMap map;
    private final Map<Integer, ServerPlayer> players = new LinkedHashMap<>();
    private final List<Bullet> bullets = new ArrayList<>();
    private final List<Pickup> pickups = new ArrayList<>();
    private final List<Message> events = new ArrayList<>();
    private final Random random = new Random();
    private long tick;
    private int nextPlayerId = 1;
    private int nextBulletId = 1;

    public World(GameMap map) {
        this.map = map;
        int id = 1;
        for (GameMap.PickupSpot spot : map.pickupSpots()) {
            pickups.add(new Pickup(id++, spot.type(), spot.x(), spot.y()));
        }
    }

    public GameMap map() { return map; }

    Collection<ServerPlayer> players() { return players.values(); }

    List<Pickup> pickups() { return pickups; }

    /** Добавляет человека. Если мест нет — выгоняет бота, а если и ботов нет, возвращает -1. */
    public int addHuman(String name) {
        if (players.size() >= MAX_PLAYERS) {
            ServerPlayer bot = players.values().stream().filter(ServerPlayer::isBot).findFirst().orElse(null);
            if (bot == null) return -1;
            players.remove(bot.id);
            events.add(new Message.Info(bot.name + " уступает место игроку " + name));
        }
        return add(name, null);
    }

    public int addBot() {
        if (players.size() >= MAX_PLAYERS) return -1;
        String name = Arrays.stream(BOT_NAMES)
                .filter(n -> players.values().stream().noneMatch(p -> p.name.equals(n)))
                .findFirst().orElse("Бот");
        return add(name, new BotBrain(map, random));
    }

    private int add(String name, BotBrain brain) {
        ServerPlayer player = new ServerPlayer(nextPlayerId++, name, freeColor(), brain);
        players.put(player.id, player);
        respawn(player);
        return player.id;
    }

    /** Убирает игрока и возвращает его имя (или null, если такого нет). */
    public String removePlayer(int id) {
        ServerPlayer removed = players.remove(id);
        return removed == null ? null : removed.name;
    }

    public void setInput(int id, int buttons, double aim) {
        ServerPlayer p = players.get(id);
        if (p == null || p.isBot()) return;
        p.buttons = buttons;
        if (Double.isFinite(aim)) p.aim = aim;
    }

    /** События, накопившиеся за тик (убийства и т.п.). После вызова список очищается. */
    public List<Message> drainEvents() {
        List<Message> copy = List.copyOf(events);
        events.clear();
        return copy;
    }

    /** Один шаг симуляции. dt — сколько секунд прошло (обычно 1/60). */
    public void update(double dt) {
        tick++;
        for (ServerPlayer p : players.values()) {
            if (p.isBot()) p.brain.think(p, this, dt);
        }
        for (ServerPlayer p : players.values()) updatePlayer(p, dt);
        pushPlayersApart();
        updateBullets(dt);
        updatePickups(dt);
    }

    private void updatePlayer(ServerPlayer p, double dt) {
        if (!p.alive) {
            p.respawnTimer -= dt;
            if (p.respawnTimer <= 0) respawn(p);
            return;
        }
        p.fireCooldown -= dt;
        p.rapidFireTimer -= dt;
        p.speedTimer -= dt;
        p.shieldTimer -= dt;
        p.angle = p.aim;

        double dx = 0;
        double dy = 0;
        if (p.pressed(UP)) dy -= 1;
        if (p.pressed(DOWN)) dy += 1;
        if (p.pressed(LEFT)) dx -= 1;
        if (p.pressed(RIGHT)) dx += 1;
        double length = Math.hypot(dx, dy);
        if (length > 0) {
            // делим на длину, чтобы по диагонали не бегать быстрее
            double step = PLAYER_SPEED * (p.speedTimer > 0 ? SPEED_MULTIPLIER : 1) * dt / length;
            p.x = map.moveX(p.x, p.y, dx * step, PLAYER_RADIUS);
            p.y = map.moveY(p.x, p.y, dy * step, PLAYER_RADIUS);
        }

        if (p.pressed(FIRE) && p.fireCooldown <= 0) {
            shoot(p);
            p.fireCooldown = FIRE_COOLDOWN / (p.rapidFireTimer > 0 ? RAPID_FIRE_MULTIPLIER : 1);
        }
    }

    private void shoot(ServerPlayer p) {
        double cos = Math.cos(p.angle);
        double sin = Math.sin(p.angle);
        double muzzle = PLAYER_RADIUS + 6;
        double x = p.x + cos * muzzle;
        double y = p.y + sin * muzzle;
        if (map.isWallAt(x, y)) return; // ствол упёрся в стену — стрелять сквозь неё нельзя
        bullets.add(new Bullet(nextBulletId++, p.id, x, y, cos * BULLET_SPEED, sin * BULLET_SPEED, BULLET_LIFETIME));
        p.shieldTimer = 0; // кто стреляет, тот теряет защиту после появления
    }

    /** Игроки не должны проходить сквозь друг друга — раздвигаем тех, кто перекрылся. */
    private void pushPlayersApart() {
        List<ServerPlayer> alive = new ArrayList<>();
        for (ServerPlayer p : players.values()) {
            if (p.alive) alive.add(p);
        }
        double minDistance = PLAYER_RADIUS * 2;
        for (int i = 0; i < alive.size(); i++) {
            for (int j = i + 1; j < alive.size(); j++) {
                ServerPlayer a = alive.get(i);
                ServerPlayer b = alive.get(j);
                double dx = b.x - a.x;
                double dy = b.y - a.y;
                double distance = Math.hypot(dx, dy);
                if (distance >= minDistance) continue;
                if (distance < 1e-6) {
                    dx = 1;
                    dy = 0;
                    distance = 1;
                }
                double push = (minDistance - distance) / 2;
                double nx = dx / distance * push;
                double ny = dy / distance * push;
                a.x = map.moveX(a.x, a.y, -nx, PLAYER_RADIUS);
                a.y = map.moveY(a.x, a.y, -ny, PLAYER_RADIUS);
                b.x = map.moveX(b.x, b.y, nx, PLAYER_RADIUS);
                b.y = map.moveY(b.x, b.y, ny, PLAYER_RADIUS);
            }
        }
    }

    private void updateBullets(double dt) {
        Iterator<Bullet> it = bullets.iterator();
        while (it.hasNext()) {
            Bullet b = it.next();
            b.x += b.vx * dt;
            b.y += b.vy * dt;
            b.life -= dt;
            if (b.life <= 0 || map.isWallAt(b.x, b.y)) {
                it.remove();
                continue;
            }
            for (ServerPlayer p : players.values()) {
                if (!p.alive || p.id == b.owner) continue;
                if (Math.hypot(p.x - b.x, p.y - b.y) < PLAYER_RADIUS + BULLET_RADIUS) {
                    it.remove();
                    damage(p, b.owner);
                    break;
                }
            }
        }
    }

    private void damage(ServerPlayer victim, int attackerId) {
        if (victim.shieldTimer > 0) return;
        victim.hp -= BULLET_DAMAGE;
        if (victim.hp > 0) return;

        victim.hp = 0;
        victim.alive = false;
        victim.deaths++;
        victim.respawnTimer = RESPAWN_TIME;
        victim.rapidFireTimer = 0;
        victim.speedTimer = 0;
        ServerPlayer attacker = players.get(attackerId);
        if (attacker != null) attacker.score++;
        events.add(new Message.Kill(attackerId, victim.id));
    }

    private void updatePickups(double dt) {
        for (Pickup pickup : pickups) {
            if (!pickup.active) {
                pickup.respawnTimer -= dt;
                if (pickup.respawnTimer <= 0) pickup.active = true;
                continue;
            }
            for (ServerPlayer p : players.values()) {
                if (!p.alive || Math.hypot(p.x - pickup.x, p.y - pickup.y) > PLAYER_RADIUS + PICKUP_RADIUS) continue;
                if (applyPickup(pickup.type, p)) {
                    pickup.active = false;
                    pickup.respawnTimer = PICKUP_RESPAWN_TIME;
                    break;
                }
            }
        }
    }

    /** Возвращает false, если бонус сейчас бесполезен (аптечка при полном здоровье). */
    private boolean applyPickup(PickupType type, ServerPlayer p) {
        switch (type) {
            case HEALTH -> {
                if (p.hp >= PLAYER_MAX_HP) return false;
                p.hp = Math.min(PLAYER_MAX_HP, p.hp + HEAL_AMOUNT);
            }
            case RAPID_FIRE -> p.rapidFireTimer = BUFF_TIME;
            case SPEED -> p.speedTimer = BUFF_TIME;
        }
        return true;
    }

    /** Возрождаем в точке, которая дальше всего от остальных игроков (с небольшой случайностью). */
    private void respawn(ServerPlayer p) {
        GameMap.Spot best = map.spawns().get(0);
        double bestScore = -1;
        for (GameMap.Spot spot : map.spawns()) {
            double nearest = 2000;
            for (ServerPlayer other : players.values()) {
                if (other != p && other.alive) {
                    nearest = Math.min(nearest, Math.hypot(other.x - spot.x(), other.y - spot.y()));
                }
            }
            double score = nearest + random.nextDouble() * 60;
            if (score > bestScore) {
                bestScore = score;
                best = spot;
            }
        }
        p.x = best.x();
        p.y = best.y();
        p.hp = PLAYER_MAX_HP;
        p.alive = true;
        p.shieldTimer = SPAWN_SHIELD_TIME;
        p.fireCooldown = 0.2;
        p.angle = Math.atan2(map.pixelHeight() / 2.0 - p.y, map.pixelWidth() / 2.0 - p.x);
        p.aim = p.angle;
    }

    private int freeColor() {
        boolean[] used = new boolean[MAX_PLAYERS];
        for (ServerPlayer p : players.values()) used[p.color] = true;
        for (int i = 0; i < used.length; i++) {
            if (!used[i]) return i;
        }
        return 0;
    }

    public Snapshot snapshot() {
        List<PlayerState> playerStates = new ArrayList<>(players.size());
        for (ServerPlayer p : players.values()) {
            int flags = 0;
            if (p.rapidFireTimer > 0) flags |= PlayerState.FLAG_RAPID_FIRE;
            if (p.speedTimer > 0) flags |= PlayerState.FLAG_SPEED;
            if (p.shieldTimer > 0) flags |= PlayerState.FLAG_SHIELD;
            if (p.isBot()) flags |= PlayerState.FLAG_BOT;
            playerStates.add(new PlayerState(p.id, p.name, p.color, (float) p.x, (float) p.y, (float) p.angle,
                    Math.max(0, p.hp), p.score, p.deaths, p.alive, (float) Math.max(0, p.respawnTimer), flags));
        }
        List<BulletState> bulletStates = new ArrayList<>(bullets.size());
        for (Bullet b : bullets) {
            bulletStates.add(new BulletState(b.id, b.owner, (float) b.x, (float) b.y));
        }
        List<PickupState> pickupStates = new ArrayList<>();
        for (Pickup k : pickups) {
            if (k.active) pickupStates.add(new PickupState(k.id, k.type, (float) k.x, (float) k.y));
        }
        return new Snapshot(tick, playerStates, bulletStates, pickupStates);
    }
}
