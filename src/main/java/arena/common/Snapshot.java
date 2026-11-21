package arena.common;

import java.util.List;

/** Снимок мира в один момент времени (тик). Сервер рассылает его клиентам 60 раз в секунду. */
public record Snapshot(long tick, List<PlayerState> players, List<BulletState> bullets, List<PickupState> pickups) {

    public record PlayerState(int id, String name, int color, float x, float y, float angle,
                              int hp, int score, int deaths, boolean alive, float respawnIn, int flags) {
        public static final int FLAG_RAPID_FIRE = 1;
        public static final int FLAG_SPEED = 2;
        public static final int FLAG_SHIELD = 4;
        public static final int FLAG_BOT = 8;

        public boolean has(int flag) {
            return (flags & flag) != 0;
        }
    }

    public record BulletState(int id, int owner, float x, float y) {}

    /** В снимок попадают только бонусы, которые сейчас лежат на карте. */
    public record PickupState(int id, PickupType type, float x, float y) {}

    public PlayerState player(int id) {
        for (PlayerState p : players) {
            if (p.id() == id) return p;
        }
        return null;
    }
}
