package arena.client;

import arena.common.Config;
import arena.common.GameMap;
import arena.common.Message;
import arena.common.Protocol;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Сетевая часть клиента. Ничего не рисует, поэтому её можно использовать и в тестах.
 * Поток чтения складывает снимки мира в буфер, поток отправки 60 раз в секунду шлёт ввод.
 */
public final class GameClient implements AutoCloseable {
    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final int playerId;
    private final GameMap map;
    private final SnapshotBuffer snapshots = new SnapshotBuffer();
    private final Queue<Message> events = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledExecutorService sender;
    private volatile int buttons;
    private volatile float aim;
    private volatile double pingMs = -1;
    private volatile String disconnectReason;
    private int sendCounter;

    /** Подключается и проходит «рукопожатие». Бросает IOException с понятным текстом, если не вышло. */
    public static GameClient connect(String host, int port, String name) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), 4000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(Config.TIMEOUT_MS);
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            Protocol.writeFrame(out, Protocol.encode(new Message.Hello(Config.PROTOCOL_VERSION, name)));
            out.flush();

            return switch (Protocol.decode(Protocol.readFrame(in))) {
                case Message.Welcome welcome ->
                        new GameClient(socket, in, out, welcome.playerId(), new GameMap(welcome.mapRows()));
                case Message.Reject reject -> throw new IOException(reject.reason());
                default -> throw new IOException("Сервер ответил что-то непонятное");
            };
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    private GameClient(Socket socket, DataInputStream in, DataOutputStream out, int playerId, GameMap map) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.playerId = playerId;
        this.map = map;
        this.sender = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "arena-client-send");
            thread.setDaemon(true);
            return thread;
        });
        Thread reader = new Thread(this::readLoop, "arena-client-read");
        reader.setDaemon(true);
        reader.start();
        sender.scheduleAtFixedRate(this::sendInput, 0, 1_000_000_000L / Config.TICK_RATE, TimeUnit.NANOSECONDS);
    }

    public int playerId() { return playerId; }
    public GameMap map() { return map; }
    SnapshotBuffer snapshots() { return snapshots; }

    /** Задержка до сервера и обратно в миллисекундах (-1, пока не измерили). */
    public double pingMs() { return pingMs; }

    /** Причина отключения или null, если всё в порядке. */
    public String disconnectReason() { return disconnectReason; }

    /** Следующее событие (убийство, сообщение) или null. */
    public Message pollEvent() { return events.poll(); }

    public void setInput(int buttons, float aim) {
        this.buttons = buttons;
        if (Float.isFinite(aim)) this.aim = aim;
    }

    private void readLoop() {
        try {
            while (!closed.get()) {
                switch (Protocol.decode(Protocol.readFrame(in))) {
                    case Message.State state -> snapshots.add(state.snapshot(), System.nanoTime());
                    case Message.Pong pong -> pingMs = (System.nanoTime() - pong.time()) / 1_000_000.0;
                    case Message.Kill kill -> events.add(kill);
                    case Message.Info info -> events.add(info);
                    default -> { }
                }
            }
        } catch (SocketTimeoutException e) {
            disconnect("Сервер не отвечает");
        } catch (IOException | RuntimeException e) {
            disconnect("Соединение с сервером разорвано");
        }
    }

    private void sendInput() {
        try {
            Protocol.writeFrame(out, Protocol.encode(new Message.Input(buttons, aim)));
            if (sendCounter++ % Config.TICK_RATE == 0) { // раз в секунду меряем пинг
                Protocol.writeFrame(out, Protocol.encode(new Message.Ping(System.nanoTime())));
            }
            out.flush();
        } catch (IOException e) {
            disconnect("Не удалось отправить данные серверу");
        }
    }

    private void disconnect(String reason) {
        if (!closed.get() && disconnectReason == null) disconnectReason = reason;
        close();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            sender.shutdownNow();
            try {
                socket.close();
            } catch (IOException ignored) {
                // уже закрыт
            }
        }
    }
}
