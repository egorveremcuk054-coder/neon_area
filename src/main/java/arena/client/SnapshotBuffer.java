package arena.client;

import arena.common.Config;
import arena.common.Snapshot;
import arena.common.Snapshot.BulletState;
import arena.common.Snapshot.PlayerState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Хранит последние снимки мира и выдаёт «плавную» картинку между ними.
 *
 * Снимки приходят 60 раз в секунду и с неровными промежутками (так работает сеть),
 * а кадры рисуются со своей частотой. Если рисовать просто последний снимок, всё будет дёргаться.
 * Поэтому показываем мир на INTERP_DELAY_MS в прошлом и плавно смешиваем два соседних снимка.
 */
final class SnapshotBuffer {
    private static final long TICK_NANOS = 1_000_000_000L / Config.TICK_RATE;
    private static final long DELAY_NANOS = Config.INTERP_DELAY_MS * 1_000_000L;
    private static final int CAPACITY = 64;

    private final ArrayDeque<Snapshot> buffer = new ArrayDeque<>();
    /** Разница между нашими часами и «часами сервера» (номер тика × длительность тика). */
    private long clockOffset;
    private boolean synced;

    synchronized void add(Snapshot snapshot, long receivedAt) {
        long sample = receivedAt - snapshot.tick() * TICK_NANOS;
        if (!synced || sample < clockOffset) {
            // Пакет пришёл быстрее обычного — значит, это ближе к реальной разнице часов.
            clockOffset = sample;
            synced = true;
        } else {
            // Медленно подстраиваемся, если сервер стал присылать данные позже.
            clockOffset += (sample - clockOffset) / 100;
        }
        buffer.addLast(snapshot);
        while (buffer.size() > CAPACITY) buffer.removeFirst();
    }

    synchronized Snapshot latest() {
        return buffer.peekLast();
    }

    /** Состояние мира для кадра, который рисуется в момент now (System.nanoTime()). */
    synchronized Snapshot sample(long now) {
        if (buffer.isEmpty()) return null;
        double renderTick = (now - DELAY_NANOS - clockOffset) / (double) TICK_NANOS;
        Snapshot older = null;
        for (Snapshot snapshot : buffer) { // от старых к новым
            if (snapshot.tick() <= renderTick) {
                older = snapshot;
                continue;
            }
            if (older == null) return snapshot;
            float t = (float) ((renderTick - older.tick()) / (snapshot.tick() - older.tick()));
            return interpolate(older, snapshot, t);
        }
        return older; // новых снимков пока нет — показываем последний
    }

    /** Смешивает два снимка: t = 0 — первый, t = 1 — второй. */
    static Snapshot interpolate(Snapshot a, Snapshot b, float t) {
        Map<Integer, PlayerState> oldPlayers = new HashMap<>();
        for (PlayerState p : a.players()) oldPlayers.put(p.id(), p);
        List<PlayerState> players = new ArrayList<>(b.players().size());
        for (PlayerState p : b.players()) {
            PlayerState o = oldPlayers.get(p.id());
            if (o == null || o.alive() != p.alive()) {
                players.add(p); // появился или возродился — не тянем его через всю карту
                continue;
            }
            players.add(new PlayerState(p.id(), p.name(), p.color(),
                    lerp(o.x(), p.x(), t), lerp(o.y(), p.y(), t), lerpAngle(o.angle(), p.angle(), t),
                    p.hp(), p.score(), p.deaths(), p.alive(), p.respawnIn(), p.flags()));
        }

        Map<Integer, BulletState> oldBullets = new HashMap<>();
        for (BulletState bullet : a.bullets()) oldBullets.put(bullet.id(), bullet);
        List<BulletState> bullets = new ArrayList<>(b.bullets().size());
        for (BulletState bullet : b.bullets()) {
            BulletState o = oldBullets.get(bullet.id());
            bullets.add(o == null ? bullet
                    : new BulletState(bullet.id(), bullet.owner(), lerp(o.x(), bullet.x(), t), lerp(o.y(), bullet.y(), t)));
        }
        return new Snapshot(b.tick(), players, bullets, b.pickups());
    }

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }

    /** Поворот по кратчайшему пути: из 350° в 10° — через 0°, а не через 180°. */
    private static float lerpAngle(float from, float to, float t) {
        double diff = Math.IEEEremainder(to - from, Math.PI * 2);
        return (float) (from + diff * t);
    }
}
