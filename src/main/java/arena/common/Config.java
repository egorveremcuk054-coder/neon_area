package arena.common;

/** Все настройки игры в одном месте — меняй числа и смотри, что получится. */
public final class Config {
    private Config() {}

    // --- Сеть ---
    public static final int DEFAULT_PORT = 7777;
    /** Увеличивай, если меняешь формат сообщений, — старые клиенты получат понятную ошибку. */
    public static final int PROTOCOL_VERSION = 1;
    /** Сколько раз в секунду сервер обновляет мир и рассылает его состояние. */
    public static final int TICK_RATE = 60;
    public static final double TICK_DT = 1.0 / TICK_RATE;
    /** Клиент показывает мир чуть «в прошлом», чтобы плавно двигать объекты между снимками. */
    public static final int INTERP_DELAY_MS = 50;
    /** Если от собеседника нет данных столько времени — считаем, что связь потеряна. */
    public static final int TIMEOUT_MS = 5000;

    // --- Игроки ---
    public static final int MAX_PLAYERS = 4;
    public static final int MAX_NAME_LENGTH = 16;
    public static final double PLAYER_RADIUS = 16;
    public static final double PLAYER_SPEED = 230;       // пикселей в секунду
    public static final int PLAYER_MAX_HP = 100;
    public static final double RESPAWN_TIME = 3.0;       // секунд
    public static final double SPAWN_SHIELD_TIME = 1.5;  // неуязвимость после появления

    // --- Оружие ---
    public static final double BULLET_SPEED = 650;
    public static final double BULLET_RADIUS = 4;
    public static final double BULLET_LIFETIME = 1.6;
    public static final int BULLET_DAMAGE = 20;
    public static final double FIRE_COOLDOWN = 0.28;

    // --- Бонусы ---
    public static final double PICKUP_RADIUS = 14;
    public static final double PICKUP_RESPAWN_TIME = 12;
    public static final int HEAL_AMOUNT = 40;
    public static final double BUFF_TIME = 6;
    public static final double RAPID_FIRE_MULTIPLIER = 2.5;
    public static final double SPEED_MULTIPLIER = 1.45;
}
