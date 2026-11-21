package arena.server;

import arena.common.Config;
import arena.common.GameMap;
import arena.common.Message;
import arena.common.Protocol;
import arena.common.Timing;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Сервер игры. Потоки:
 *  - accept: принимает новые подключения;
 *  - game loop: 60 раз в секунду обновляет мир и рассылает снимок;
 *  - по два потока на каждого клиента (чтение и запись), см. ClientConnection.
 * Мир общий для всех потоков, поэтому любое обращение к нему — под блокировкой lock.
 */
public final class GameServer implements AutoCloseable {
    private final int port;
    private final ServerSocket serverSocket;
    private final World world;
    private final Object lock = new Object();
    private final List<ClientConnection> clients = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;
    private Thread gameLoopThread;

    public GameServer(int port, int bots) throws IOException {
        this.port = port;
        this.world = new World(GameMap.createDefault());
        this.serverSocket = new ServerSocket(port);
        for (int i = 0; i < bots; i++) world.addBot();
    }

    public void start() {
        startThread("arena-accept", this::acceptLoop);
        gameLoopThread = startThread("arena-game-loop", this::gameLoop);
        log("Запущен на порту " + port + ". Адреса для подключения: " + String.join(", ", localAddresses()));
    }

    /** Блокирует текущий поток, пока сервер работает (нужно для режима --server). */
    public void awaitTermination() throws InterruptedException {
        gameLoopThread.join();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                // Рукопожатие — в отдельном потоке, чтобы медленный клиент не мешал принимать остальных.
                startThread("arena-handshake", () -> handshake(socket));
            } catch (IOException e) {
                if (running) log("Ошибка при приёме подключения: " + e.getMessage());
            }
        }
    }

    private void handshake(Socket socket) {
        int playerId = -1;
        boolean registered = false;
        try {
            socket.setTcpNoDelay(true); // отправлять маленькие пакеты сразу, без накопления
            socket.setSoTimeout(Config.TIMEOUT_MS);
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            if (!(Protocol.decode(Protocol.readFrame(in)) instanceof Message.Hello hello)) {
                socket.close();
                return;
            }
            if (hello.version() != Config.PROTOCOL_VERSION) {
                reject(out, socket, "У вас и у сервера разные версии игры");
                return;
            }
            String name = cleanName(hello.name());
            synchronized (lock) {
                playerId = world.addHuman(name);
            }
            if (playerId < 0) {
                reject(out, socket, "Арена заполнена: максимум " + Config.MAX_PLAYERS + " игрока");
                return;
            }

            Protocol.writeFrame(out, Protocol.encode(new Message.Welcome(playerId, world.map().rows())));
            out.flush();
            ClientConnection connection = new ClientConnection(this, playerId, socket, in, out);
            clients.add(connection);
            registered = true;
            connection.start();
            log("Подключение: " + name + " (" + socket.getInetAddress().getHostAddress() + ")");
            broadcast(new Message.Info(name + " выходит на арену"));
        } catch (IOException | RuntimeException e) {
            closeQuietly(socket);
            if (playerId > 0 && !registered) {
                synchronized (lock) {
                    world.removePlayer(playerId);
                }
            }
        }
    }

    private void gameLoop() {
        final long tickNanos = 1_000_000_000L / Config.TICK_RATE;
        long nextTick = System.nanoTime();
        while (running) {
            try {
                List<Message> events;
                byte[] state;
                synchronized (lock) {
                    for (ClientConnection c : clients) world.setInput(c.playerId(), c.buttons(), c.aim());
                    world.update(Config.TICK_DT);
                    events = world.drainEvents();
                    state = Protocol.encode(new Message.State(world.snapshot()));
                }
                for (Message event : events) broadcast(event);
                for (ClientConnection c : clients) c.sendState(state);
            } catch (RuntimeException e) {
                log("Ошибка в игровом цикле: " + e);
                e.printStackTrace();
            }

            nextTick += tickNanos;
            long now = System.nanoTime();
            if (now - nextTick > tickNanos * 10) nextTick = now; // сильно отстали (компьютер «подвис») — не догоняем
            Timing.sleepUntil(nextTick);
        }
    }

    void broadcast(Message message) {
        byte[] frame = Protocol.encode(message);
        for (ClientConnection c : clients) c.sendReliable(frame);
    }

    void onDisconnect(ClientConnection connection) {
        if (!clients.remove(connection)) return;
        String name;
        synchronized (lock) {
            name = world.removePlayer(connection.playerId());
        }
        if (name != null && running) {
            log("Отключение: " + name);
            broadcast(new Message.Info(name + " покидает арену"));
        }
    }

    @Override
    public void close() {
        running = false;
        closeQuietly(serverSocket);
        for (ClientConnection c : clients) c.close();
    }

    private static void reject(DataOutputStream out, Socket socket, String reason) throws IOException {
        Protocol.writeFrame(out, Protocol.encode(new Message.Reject(reason)));
        out.flush();
        socket.close();
    }

    static String cleanName(String raw) {
        String name = raw == null ? "" : raw.replaceAll("\\p{Cntrl}", "").strip();
        if (name.length() > Config.MAX_NAME_LENGTH) name = name.substring(0, Config.MAX_NAME_LENGTH);
        return name.isEmpty() ? "Игрок" : name;
    }

    /** IPv4-адреса этого компьютера в локальной сети — их нужно сообщить другу. */
    public static List<String> localAddresses() {
        List<String> result = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress address : Collections.list(ni.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLinkLocalAddress()) {
                        result.add(address.getHostAddress());
                    }
                }
            }
        } catch (SocketException ignored) {
            // не смогли перечислить сетевые карты — ничего страшного
        }
        // Домашние сети обычно 192.168.x.x — показываем их первыми.
        result.sort(Comparator.comparingInt(a -> a.startsWith("192.168.") ? 0 : a.startsWith("10.") ? 1 : 2));
        if (result.isEmpty()) result.add("127.0.0.1");
        return result;
    }

    static Thread startThread(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // при закрытии ошибки уже не важны
        }
    }

    private static void log(String message) {
        System.out.println("[сервер] " + message);
    }
}
