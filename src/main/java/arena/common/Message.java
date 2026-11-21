package arena.common;

import java.util.List;

/**
 * Все сообщения, которыми обмениваются клиент и сервер.
 * sealed-интерфейс: компилятор знает полный список вариантов и проверит,
 * что в switch мы ничего не забыли.
 */
public sealed interface Message {

    /** Клиент → сервер: «привет, я хочу играть». */
    record Hello(int version, String name) implements Message {}

    /** Сервер → клиент: «добро пожаловать, твой id такой-то, вот карта». */
    record Welcome(int playerId, List<String> mapRows) implements Message {}

    /** Сервер → клиент: отказ (арена заполнена, другая версия и т.п.). */
    record Reject(String reason) implements Message {}

    /** Клиент → сервер, 60 раз в секунду: какие кнопки зажаты и куда смотрит мышь. */
    record Input(int buttons, float aim) implements Message {
        public static final int UP = 1;
        public static final int DOWN = 2;
        public static final int LEFT = 4;
        public static final int RIGHT = 8;
        public static final int FIRE = 16;
    }

    /** Замер задержки: клиент шлёт своё время, сервер сразу возвращает его обратно. */
    record Ping(long time) implements Message {}

    record Pong(long time) implements Message {}

    /** Сервер → клиент, 60 раз в секунду: состояние всего мира. */
    record State(Snapshot snapshot) implements Message {}

    /** Сервер → клиент: кто кого сбил (для ленты событий). */
    record Kill(int killerId, int victimId) implements Message {}

    /** Сервер → клиент: текстовое событие («Вася вышел на арену»). */
    record Info(String text) implements Message {}
}
