package arena.common;

import arena.common.Snapshot.BulletState;
import arena.common.Snapshot.PickupState;
import arena.common.Snapshot.PlayerState;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Превращает сообщения в байты и обратно.
 * В TCP-потоке каждый пакет выглядит так: [длина: int][тип: byte][данные...].
 * Длина нужна потому, что TCP — это сплошной поток байтов, а не отдельные сообщения.
 */
public final class Protocol {
    private Protocol() {}

    private static final int MAX_FRAME_SIZE = 1 << 20;

    private static final byte HELLO = 1;
    private static final byte WELCOME = 2;
    private static final byte REJECT = 3;
    private static final byte INPUT = 4;
    private static final byte PING = 5;
    private static final byte PONG = 6;
    private static final byte STATE = 7;
    private static final byte KILL = 8;
    private static final byte INFO = 9;

    public static byte[] encode(Message message) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(128);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            switch (message) {
                case Message.Hello m -> {
                    out.writeByte(HELLO);
                    out.writeInt(m.version());
                    out.writeUTF(m.name());
                }
                case Message.Welcome m -> {
                    out.writeByte(WELCOME);
                    out.writeInt(m.playerId());
                    out.writeShort(m.mapRows().size());
                    for (String row : m.mapRows()) out.writeUTF(row);
                }
                case Message.Reject m -> {
                    out.writeByte(REJECT);
                    out.writeUTF(m.reason());
                }
                case Message.Input m -> {
                    out.writeByte(INPUT);
                    out.writeByte(m.buttons());
                    out.writeFloat(m.aim());
                }
                case Message.Ping m -> {
                    out.writeByte(PING);
                    out.writeLong(m.time());
                }
                case Message.Pong m -> {
                    out.writeByte(PONG);
                    out.writeLong(m.time());
                }
                case Message.State m -> {
                    out.writeByte(STATE);
                    writeSnapshot(out, m.snapshot());
                }
                case Message.Kill m -> {
                    out.writeByte(KILL);
                    out.writeInt(m.killerId());
                    out.writeInt(m.victimId());
                }
                case Message.Info m -> {
                    out.writeByte(INFO);
                    out.writeUTF(m.text());
                }
            }
        } catch (IOException e) {
            // Запись в память упасть не может, но компилятор об этом не знает.
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    public static Message decode(byte[] frame) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame));
        byte type = in.readByte();
        return switch (type) {
            case HELLO -> new Message.Hello(in.readInt(), in.readUTF());
            case WELCOME -> {
                int playerId = in.readInt();
                int count = in.readUnsignedShort();
                List<String> rows = new ArrayList<>(count);
                for (int i = 0; i < count; i++) rows.add(in.readUTF());
                yield new Message.Welcome(playerId, rows);
            }
            case REJECT -> new Message.Reject(in.readUTF());
            case INPUT -> new Message.Input(in.readUnsignedByte(), in.readFloat());
            case PING -> new Message.Ping(in.readLong());
            case PONG -> new Message.Pong(in.readLong());
            case STATE -> new Message.State(readSnapshot(in));
            case KILL -> new Message.Kill(in.readInt(), in.readInt());
            case INFO -> new Message.Info(in.readUTF());
            default -> throw new IOException("Неизвестный тип сообщения: " + type);
        };
    }

    /** Пишет пакет в поток. Не забудь потом вызвать out.flush(). */
    public static void writeFrame(DataOutputStream out, byte[] payload) throws IOException {
        out.writeInt(payload.length);
        out.write(payload);
    }

    /** Читает один пакет целиком (блокируется, пока он не придёт). */
    public static byte[] readFrame(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length <= 0 || length > MAX_FRAME_SIZE) {
            throw new IOException("Некорректный размер пакета: " + length);
        }
        byte[] payload = new byte[length];
        in.readFully(payload);
        return payload;
    }

    private static void writeSnapshot(DataOutputStream out, Snapshot snapshot) throws IOException {
        out.writeLong(snapshot.tick());

        out.writeByte(snapshot.players().size());
        for (PlayerState p : snapshot.players()) {
            out.writeInt(p.id());
            out.writeUTF(p.name());
            out.writeByte(p.color());
            out.writeFloat(p.x());
            out.writeFloat(p.y());
            out.writeFloat(p.angle());
            out.writeShort(p.hp());
            out.writeShort(p.score());
            out.writeShort(p.deaths());
            out.writeBoolean(p.alive());
            out.writeFloat(p.respawnIn());
            out.writeByte(p.flags());
        }

        out.writeShort(snapshot.bullets().size());
        for (BulletState b : snapshot.bullets()) {
            out.writeInt(b.id());
            out.writeInt(b.owner());
            out.writeFloat(b.x());
            out.writeFloat(b.y());
        }

        out.writeByte(snapshot.pickups().size());
        for (PickupState k : snapshot.pickups()) {
            out.writeInt(k.id());
            out.writeByte(k.type().ordinal());
            out.writeFloat(k.x());
            out.writeFloat(k.y());
        }
    }

    private static Snapshot readSnapshot(DataInputStream in) throws IOException {
        long tick = in.readLong();

        int playerCount = in.readUnsignedByte();
        List<PlayerState> players = new ArrayList<>(playerCount);
        for (int i = 0; i < playerCount; i++) {
            players.add(new PlayerState(
                    in.readInt(), in.readUTF(), in.readUnsignedByte(),
                    in.readFloat(), in.readFloat(), in.readFloat(),
                    in.readShort(), in.readShort(), in.readShort(),
                    in.readBoolean(), in.readFloat(), in.readUnsignedByte()));
        }

        int bulletCount = in.readUnsignedShort();
        List<BulletState> bullets = new ArrayList<>(bulletCount);
        for (int i = 0; i < bulletCount; i++) {
            bullets.add(new BulletState(in.readInt(), in.readInt(), in.readFloat(), in.readFloat()));
        }

        int pickupCount = in.readUnsignedByte();
        List<PickupState> pickups = new ArrayList<>(pickupCount);
        for (int i = 0; i < pickupCount; i++) {
            int id = in.readInt();
            int type = in.readUnsignedByte();
            if (type >= PickupType.values().length) throw new IOException("Неизвестный бонус: " + type);
            pickups.add(new PickupState(id, PickupType.byId(type), in.readFloat(), in.readFloat()));
        }

        return new Snapshot(tick, players, bullets, pickups);
    }
}
