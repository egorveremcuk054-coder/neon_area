package arena.server;

import arena.common.Config;

/** Игрок на стороне сервера: полное состояние, включая таймеры, которые клиенту знать не нужно. */
final class ServerPlayer {
    final int id;
    final String name;
    final int color;
    final BotBrain brain; // null, если это живой человек

    double x;
    double y;
    double angle;
    int hp = Config.PLAYER_MAX_HP;
    int score;
    int deaths;
    boolean alive;

    double respawnTimer;
    double fireCooldown;
    double rapidFireTimer;
    double speedTimer;
    double shieldTimer;

    // Последний ввод: от клиента по сети или от «мозга» бота.
    int buttons;
    double aim;

    ServerPlayer(int id, String name, int color, BotBrain brain) {
        this.id = id;
        this.name = name;
        this.color = color;
        this.brain = brain;
    }

    boolean isBot() {
        return brain != null;
    }

    boolean pressed(int button) {
        return (buttons & button) != 0;
    }
}
