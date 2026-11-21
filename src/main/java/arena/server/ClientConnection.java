package arena.server;

import arena.common.Message;
import arena.common.Protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Одно подключение клиента на сервере.
 * Поток чтения принимает ввод, поток записи отправляет пакеты из очереди.
 * Благодаря очереди медленный клиент не тормозит игровой цикл.
 */
final class ClientConnection {
    /** Если в очереди уже столько пакетов, свежий снимок мира пропускаем: клиент не успевает. */
    private static final int MAX_QUEUED_STATES = 4;

    private final GameServer server;
    private final int playerId;
    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final BlockingQueue<byte[]> outbox = new LinkedBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile int buttons;
    private volatile float aim;

    ClientConnection(GameServer server, int playerId, Socket socket, DataInputStream in, DataOutputStream out) {
        this.server = server;
        this.playerId = playerId;
        this.socket = socket;
        this.in = in;
        this.out = out;
    }

    int playerId() { return playerId; }
    int buttons() { return buttons; }
    float aim() { return aim; }

    void start() {
        GameServer.startThread("arena-read-" + playerId, this::readLoop);
        GameServer.startThread("arena-write-" + playerId, this::writeLoop);
    }

    private void readLoop() {
        try {
            while (!closed.get()) {
                switch (Protocol.decode(Protocol.readFrame(in))) {
                    case Message.Input input -> {
                        buttons = input.buttons();
                        aim = input.aim();
                    }
                    case Message.Ping ping -> sendReliable(Protocol.encode(new Message.Pong(ping.time())));
                    default -> { } // остальные сообщения от клиента не ожидаются
                }
            }
        } catch (IOException | RuntimeException e) {
            // клиент отключился, завис или прислал мусор
        } finally {
            close();
        }
    }

    private void writeLoop() {
        try {
            while (!closed.get()) {
                byte[] frame = outbox.poll(500, TimeUnit.MILLISECONDS);
                if (frame == null) continue;
                Protocol.writeFrame(out, frame);
                // Отправляем всё, что накопилось, одним flush — так меньше сетевых пакетов.
                while ((frame = outbox.poll()) != null) Protocol.writeFrame(out, frame);
                out.flush();
            }
        } catch (IOException | InterruptedException e) {
            // соединение закрыто
        } finally {
            close();
        }
    }

    /** Важные сообщения (события, понг) — всегда в очередь. */
    void sendReliable(byte[] frame) {
        if (!closed.get()) outbox.offer(frame);
    }

    /** Снимки мира устаревают за 16 мс, поэтому их можно пропускать, если клиент не успевает. */
    void sendState(byte[] frame) {
        if (!closed.get() && outbox.size() < MAX_QUEUED_STATES) outbox.offer(frame);
    }

    void close() {
        if (closed.compareAndSet(false, true)) {
            GameServer.closeQuietly(socket);
            server.onDisconnect(this);
        }
    }
}
