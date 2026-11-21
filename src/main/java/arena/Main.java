package arena;

import arena.client.Launcher;
import arena.common.Config;
import arena.server.GameServer;

import javax.swing.SwingUtilities;

/**
 * Точка входа.
 *   java -jar arena.jar                      — окно запуска (создать игру / подключиться)
 *   java -jar arena.jar --server [порт] [боты] — только сервер, без окна
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--server")) {
            int port = args.length > 1 ? Integer.parseInt(args[1]) : Config.DEFAULT_PORT;
            int bots = args.length > 2 ? Integer.parseInt(args[2]) : 0;
            GameServer server = new GameServer(port, bots);
            server.start();
            server.awaitTermination();
            return;
        }
        SwingUtilities.invokeLater(Launcher::open);
    }
}
