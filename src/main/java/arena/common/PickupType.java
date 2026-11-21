package arena.common;

/** Виды бонусов, которые лежат на карте. */
public enum PickupType {
    HEALTH,      // аптечка
    RAPID_FIRE,  // скорострельность
    SPEED;       // ускорение

    private static final PickupType[] VALUES = values();

    public static PickupType byId(int id) {
        if (id < 0 || id >= VALUES.length) {
            throw new IllegalArgumentException("Неизвестный бонус: " + id);
        }
        return VALUES[id];
    }
}
