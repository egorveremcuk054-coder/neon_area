package arena.server;

import arena.common.PickupType;

/** Бонус на карте. После того как его подобрали, он появляется снова через некоторое время. */
final class Pickup {
    final int id;
    final PickupType type;
    final double x;
    final double y;
    boolean active = true;
    double respawnTimer;

    Pickup(int id, PickupType type, double x, double y) {
        this.id = id;
        this.type = type;
        this.x = x;
        this.y = y;
    }
}
