package arena.client;

import arena.common.GameMap;
import arena.common.Message;
import arena.common.PickupType;
import arena.common.Snapshot;
import arena.common.Snapshot.BulletState;
import arena.common.Snapshot.PickupState;
import arena.common.Snapshot.PlayerState;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static arena.common.Config.*;

/**
 * Рисует кадр: карту, корабли, пули, бонусы, частицы и интерфейс.
 * Всё рисуется в «виртуальных» координатах (карта 1280×720 плюс панель сверху),
 * а потом масштабируется под размер окна с сохранением пропорций.
 */
final class Renderer {
    static final int HUD_HEIGHT = 48;

    private static final Color[] PLAYER_COLORS = {
            new Color(0x3CF0FF), new Color(0xFF4FD8), new Color(0x9DFF4F), new Color(0xFFB13C)};
    private static final Color BACKGROUND = new Color(0x0B0F1A);
    private static final Color GRID = new Color(0x121A2C);
    private static final Color WALL = new Color(0x182040);
    private static final Color WALL_EDGE = new Color(0x4C6BFF);
    private static final Color HUD_BACKGROUND = new Color(0x0E1424);
    private static final Color TEXT = new Color(0xE6ECFF);
    private static final Color TEXT_DIM = new Color(0x8A94B8);
    private static final Color DANGER = new Color(0xFF4D5E);
    private static final Color LETTERBOX = new Color(0x05070D);
    private static final Shape SHIP = createShip();

    private final Font fontSmall = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    private final Font fontBold = new Font(Font.SANS_SERIF, Font.BOLD, 13);
    private final Font fontMedium = new Font(Font.SANS_SERIF, Font.BOLD, 18);
    private final Font fontBig = new Font(Font.SANS_SERIF, Font.BOLD, 40);

    private final GameClient client;
    private final GameMap map;
    private final String hostInfo;
    private final int worldWidth;
    private final int worldHeight;
    private final int viewWidth;
    private final int viewHeight;
    private final Random random = new Random();
    private final List<Particle> particles = new ArrayList<>();
    private final Deque<FeedLine> feed = new ArrayDeque<>();

    private double scale = 1;
    private double offsetX;
    private double offsetY;
    private float localAim;
    private double time;
    private double hitFlash;
    private double shake;
    private int lastHp = -1;
    private String lastKiller;
    private Snapshot previous;

    Renderer(GameClient client, String hostInfo) {
        this.client = client;
        this.map = client.map();
        this.hostInfo = hostInfo;
        this.worldWidth = map.pixelWidth();
        this.worldHeight = map.pixelHeight();
        this.viewWidth = worldWidth;
        this.viewHeight = worldHeight + HUD_HEIGHT;
    }

    int viewWidth() { return viewWidth; }
    int viewHeight() { return viewHeight; }

    /**
     * Угол от нашего корабля к курсору мыши. Свой корабль поворачиваем сразу по этому углу,
     * не дожидаясь ответа сервера, — так прицел ощущается мгновенным.
     */
    float aimAngle(Snapshot view, int mouseX, int mouseY) {
        PlayerState me = view == null ? null : view.player(client.playerId());
        if (me == null || scale <= 0) return localAim;
        double worldX = (mouseX - offsetX) / scale;
        double worldY = (mouseY - offsetY) / scale - HUD_HEIGHT;
        localAim = (float) Math.atan2(worldY - me.y(), worldX - me.x());
        return localAim;
    }

    void render(Graphics2D g, int width, int height, Snapshot view, double dt, boolean showScores) {
        time += dt;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(LETTERBOX);
        g.fillRect(0, 0, width, height);

        // Подгоняем виртуальный экран под окно, сохраняя пропорции (по краям — тёмные полосы).
        scale = Math.min(width / (double) viewWidth, height / (double) viewHeight);
        offsetX = (width - viewWidth * scale) / 2;
        offsetY = (height - viewHeight * scale) / 2;
        AffineTransform original = g.getTransform();
        g.translate(offsetX, offsetY);
        g.scale(scale, scale);
        AffineTransform screen = g.getTransform();

        handleEvents();
        if (view != null) detectChanges(view);
        updateEffects(dt);

        // --- Мир ---
        g.translate(0, HUD_HEIGHT);
        if (shake > 0) g.translate((random.nextDouble() - 0.5) * shake, (random.nextDouble() - 0.5) * shake);
        drawFloor(g);
        drawWalls(g);
        if (view != null) {
            drawPickups(g, view);
            drawBullets(g, view);
            drawPlayers(g, view);
        }
        drawParticles(g);

        // --- Интерфейс поверх мира ---
        g.setTransform(screen);
        PlayerState me = view == null ? null : view.player(client.playerId());
        drawHitFlash(g);
        drawHud(g, view, me);
        drawFeed(g);
        drawHelp(g);
        if (me != null && !me.alive()) drawDeathScreen(g, me);
        if (showScores && view != null) drawScoreboard(g, view);
        String reason = client.disconnectReason();
        if (reason != null) {
            drawMessage(g, reason, "Нажмите Esc, чтобы выйти");
        } else if (view == null) {
            drawMessage(g, "Подключение...", "Ждём данные от сервера");
        }

        g.setTransform(original);
    }

    // ------------------------------------------------------------------ события и эффекты

    private void handleEvents() {
        Message event;
        while ((event = client.pollEvent()) != null) {
            switch (event) {
                case Message.Kill kill -> {
                    Snapshot latest = client.snapshots().latest();
                    String killer = nameOf(latest, kill.killerId());
                    String victim = nameOf(latest, kill.victimId());
                    feed.addLast(new FeedLine(killer, colorOf(latest, kill.killerId()), "  →  ",
                            victim, colorOf(latest, kill.victimId()), time));
                    if (kill.victimId() == client.playerId()) lastKiller = killer;
                }
                case Message.Info info -> feed.addLast(new FeedLine(info.text(), TEXT_DIM, "", "", TEXT_DIM, time));
                default -> { }
            }
            while (feed.size() > 5) feed.removeFirst();
        }
    }

    /** Сравниваем с прошлым кадром: исчезла пуля — искры, погиб игрок — взрыв. */
    private void detectChanges(Snapshot view) {
        if (previous != null) {
            Set<Integer> bulletIds = new HashSet<>();
            for (BulletState b : view.bullets()) bulletIds.add(b.id());
            for (BulletState b : previous.bullets()) {
                if (!bulletIds.contains(b.id())) burst(b.x(), b.y(), colorOf(previous, b.owner()), 7, 140, 0.3, 2.5);
            }
            for (PlayerState p : view.players()) {
                PlayerState before = previous.player(p.id());
                if (before == null) continue;
                if (before.alive() && !p.alive()) {
                    burst(before.x(), before.y(), color(p.color()), 40, 260, 0.9, 4);
                    burst(before.x(), before.y(), Color.WHITE, 12, 120, 0.5, 3);
                    if (p.id() == client.playerId()) shake = 12;
                } else if (!before.alive() && p.alive()) {
                    burst(p.x(), p.y(), color(p.color()), 18, 90, 0.6, 3);
                }
            }
        }
        PlayerState me = view.player(client.playerId());
        if (me != null) {
            if (lastHp >= 0 && me.alive() && me.hp() < lastHp) hitFlash = 0.35;
            lastHp = me.hp();
        }
        previous = view;
    }

    private void updateEffects(double dt) {
        hitFlash = Math.max(0, hitFlash - dt);
        shake = Math.max(0, shake - dt * 25);
        double drag = Math.max(0, 1 - 3 * dt);
        Iterator<Particle> it = particles.iterator();
        while (it.hasNext()) {
            Particle p = it.next();
            p.life -= dt;
            if (p.life <= 0) {
                it.remove();
                continue;
            }
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.vx *= drag;
            p.vy *= drag;
        }
        feed.removeIf(line -> time - line.createdAt() > 6);
    }

    private void burst(double x, double y, Color color, int count, double speed, double life, double size) {
        for (int i = 0; i < count && particles.size() < 800; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double velocity = speed * (0.3 + random.nextDouble() * 0.7);
            particles.add(new Particle(x, y, Math.cos(angle) * velocity, Math.sin(angle) * velocity,
                    life * (0.6 + random.nextDouble() * 0.4), size, color));
        }
    }

    // ------------------------------------------------------------------ мир

    private void drawFloor(Graphics2D g) {
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, worldWidth, worldHeight);
        g.setColor(GRID);
        g.setStroke(new BasicStroke(1f));
        for (int x = 0; x <= worldWidth; x += GameMap.TILE) g.drawLine(x, 0, x, worldHeight);
        for (int y = 0; y <= worldHeight; y += GameMap.TILE) g.drawLine(0, y, worldWidth, y);
    }

    private void drawWalls(Graphics2D g) {
        int tile = GameMap.TILE;
        g.setColor(WALL);
        for (int ty = 0; ty < map.height(); ty++) {
            for (int tx = 0; tx < map.width(); tx++) {
                if (map.isWall(tx, ty)) g.fillRect(tx * tile, ty * tile, tile, tile);
            }
        }
        // Светящийся контур только там, где стена граничит с полом. Два прохода: широкое свечение и яркая линия.
        EdgePass[] passes = {new EdgePass(7f, withAlpha(WALL_EDGE, 45)), new EdgePass(2f, WALL_EDGE)};
        for (EdgePass pass : passes) {
            g.setStroke(new BasicStroke(pass.width(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(pass.color());
            for (int ty = 0; ty < map.height(); ty++) {
                for (int tx = 0; tx < map.width(); tx++) {
                    if (!map.isWall(tx, ty)) continue;
                    int x = tx * tile;
                    int y = ty * tile;
                    if (!map.isWall(tx, ty - 1)) g.drawLine(x, y, x + tile, y);
                    if (!map.isWall(tx, ty + 1)) g.drawLine(x, y + tile, x + tile, y + tile);
                    if (!map.isWall(tx - 1, ty)) g.drawLine(x, y, x, y + tile);
                    if (!map.isWall(tx + 1, ty)) g.drawLine(x + tile, y, x + tile, y + tile);
                }
            }
        }
    }

    private void drawPickups(Graphics2D g, Snapshot view) {
        for (PickupState k : view.pickups()) {
            Color c = pickupColor(k.type());
            double r = PICKUP_RADIUS + Math.sin(time * 4 + k.id()) * 2;
            glow(g, k.x(), k.y(), r * 2.6, c, 110);
            g.setColor(withAlpha(c, 40));
            g.fill(circle(k.x(), k.y(), r));
            g.setColor(c);
            g.setStroke(new BasicStroke(2f));
            g.draw(circle(k.x(), k.y(), r));
            drawPickupIcon(g, k.type(), k.x(), k.y(), c);
        }
    }

    private void drawPickupIcon(Graphics2D g, PickupType type, double x, double y, Color c) {
        AffineTransform saved = g.getTransform();
        g.translate(x, y);
        g.setColor(c);
        switch (type) {
            case HEALTH -> {
                g.fill(new Rectangle2D.Double(-2.5, -7, 5, 14));
                g.fill(new Rectangle2D.Double(-7, -2.5, 14, 5));
            }
            case RAPID_FIRE -> {
                Path2D bolt = new Path2D.Double();
                bolt.moveTo(2, -9);
                bolt.lineTo(-5, 1);
                bolt.lineTo(0, 1);
                bolt.lineTo(-2, 9);
                bolt.lineTo(5, -1);
                bolt.lineTo(0, -1);
                bolt.closePath();
                g.fill(bolt);
            }
            case SPEED -> {
                g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                Path2D chevrons = new Path2D.Double();
                chevrons.moveTo(-6, -6);
                chevrons.lineTo(-1, 0);
                chevrons.lineTo(-6, 6);
                chevrons.moveTo(1, -6);
                chevrons.lineTo(6, 0);
                chevrons.lineTo(1, 6);
                g.draw(chevrons);
            }
        }
        g.setTransform(saved);
    }

    private void drawBullets(Graphics2D g, Snapshot view) {
        for (BulletState b : view.bullets()) {
            Color c = colorOf(view, b.owner());
            glow(g, b.x(), b.y(), 14, c, 160);
            g.setColor(c);
            g.fill(circle(b.x(), b.y(), BULLET_RADIUS));
            g.setColor(Color.WHITE);
            g.fill(circle(b.x(), b.y(), BULLET_RADIUS * 0.5));
        }
    }

    private void drawPlayers(Graphics2D g, Snapshot view) {
        for (PlayerState p : view.players()) {
            if (!p.alive()) continue;
            boolean isMe = p.id() == client.playerId();
            Color c = color(p.color());
            double angle = isMe ? localAim : p.angle();
            double r = PLAYER_RADIUS;

            glow(g, p.x(), p.y(), 46, c, 70);

            if (p.has(PlayerState.FLAG_SHIELD)) { // мигающий щит после появления
                double pulse = 0.5 + 0.5 * Math.sin(time * 18);
                g.setColor(withAlpha(c, (int) (60 + 140 * pulse)));
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f,
                        new float[]{6, 6}, (float) (time * 30)));
                g.draw(circle(p.x(), p.y(), r + 9));
            }
            if (p.has(PlayerState.FLAG_RAPID_FIRE)) { // вращающиеся дуги
                g.setColor(pickupColor(PickupType.RAPID_FIRE));
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                double start = (time * 400) % 360;
                for (int i = 0; i < 2; i++) {
                    g.draw(new Arc2D.Double(p.x() - r - 6, p.y() - r - 6, (r + 6) * 2, (r + 6) * 2,
                            start + i * 180, 70, Arc2D.OPEN));
                }
            }

            AffineTransform saved = g.getTransform();
            g.translate(p.x(), p.y());
            g.rotate(angle);
            if (p.has(PlayerState.FLAG_SPEED)) { // пламя из двигателя
                double length = 10 + random.nextDouble() * 8;
                Path2D flame = new Path2D.Double();
                flame.moveTo(-9, -5);
                flame.lineTo(-9 - length, 0);
                flame.lineTo(-9, 5);
                flame.closePath();
                g.setColor(pickupColor(PickupType.SPEED));
                g.fill(flame);
            }
            g.setColor(new Color(c.getRed() / 4, c.getGreen() / 4, c.getBlue() / 4));
            g.fill(SHIP);
            g.setColor(c);
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(SHIP);
            g.setTransform(saved);

            // Имя над кораблём и полоска здоровья под ним.
            g.setFont(isMe ? fontBold : fontSmall);
            drawCentered(g, p.name(), p.x(), p.y() - r - 12, isMe ? c : TEXT);
            double barWidth = 36;
            double fraction = p.hp() / (double) PLAYER_MAX_HP;
            g.setColor(new Color(0, 0, 0, 160));
            g.fill(new Rectangle2D.Double(p.x() - barWidth / 2, p.y() + r + 8, barWidth, 4));
            g.setColor(hpColor(fraction));
            g.fill(new Rectangle2D.Double(p.x() - barWidth / 2, p.y() + r + 8, barWidth * fraction, 4));
        }
    }

    private void drawParticles(Graphics2D g) {
        for (Particle p : particles) {
            double k = p.life / p.maxLife;
            g.setColor(withAlpha(p.color, (int) (255 * k)));
            double s = p.size * (0.4 + 0.6 * k);
            g.fill(new Ellipse2D.Double(p.x - s, p.y - s, s * 2, s * 2));
        }
    }

    // ------------------------------------------------------------------ интерфейс

    private void drawHud(Graphics2D g, Snapshot view, PlayerState me) {
        g.setColor(HUD_BACKGROUND);
        g.fillRect(0, 0, viewWidth, HUD_HEIGHT);
        g.setColor(withAlpha(WALL_EDGE, 120));
        g.fillRect(0, HUD_HEIGHT - 2, viewWidth, 2);

        // Здоровье
        int hp = me == null ? 0 : me.hp();
        double fraction = hp / (double) PLAYER_MAX_HP;
        g.setFont(fontBold);
        g.setColor(TEXT_DIM);
        g.drawString("HP", 16, 29);
        int barX = 44, barY = 15, barWidth = 200, barHeight = 18;
        g.setColor(new Color(0x1B2238));
        g.fillRoundRect(barX, barY, barWidth, barHeight, 9, 9);
        if (fraction > 0) {
            g.setColor(hpColor(fraction));
            g.fillRoundRect(barX, barY, (int) Math.round(barWidth * fraction), barHeight, 9, 9);
        }
        drawCentered(g, hp + " / " + PLAYER_MAX_HP, barX + barWidth / 2.0, barY + 13.5, TEXT);

        // Активные бонусы
        int x = barX + barWidth + 14;
        if (me != null && me.has(PlayerState.FLAG_RAPID_FIRE)) {
            x = drawTag(g, "ОГОНЬ ×" + RAPID_FIRE_MULTIPLIER, x, pickupColor(PickupType.RAPID_FIRE));
        }
        if (me != null && me.has(PlayerState.FLAG_SPEED)) {
            drawTag(g, "СКОРОСТЬ", x, pickupColor(PickupType.SPEED));
        }

        // Счёт всех игроков
        if (view != null) {
            int sx = 480;
            for (PlayerState p : sortedByScore(view)) {
                boolean isMe = p.id() == client.playerId();
                Color c = color(p.color());
                g.setColor(c);
                g.fill(circle(sx + 5, 24, 5));
                g.setFont(isMe ? fontBold : fontSmall);
                String text = p.name() + "  " + p.score();
                g.setColor(isMe ? c : TEXT);
                g.drawString(text, sx + 16, 29);
                sx += 16 + g.getFontMetrics().stringWidth(text) + 24;
            }
        }

        // Пинг и адрес сервера
        g.setFont(fontSmall);
        double ping = client.pingMs();
        String pingText = ping < 0 ? "Пинг: —" : ping < 1 ? "Пинг: <1 мс" : String.format("Пинг: %.0f мс", ping);
        drawRight(g, pingText, viewWidth - 16, hostInfo == null ? 29 : 21, TEXT_DIM);
        if (hostInfo != null) drawRight(g, hostInfo, viewWidth - 16, 38, TEXT);
    }

    private int drawTag(Graphics2D g, String text, int x, Color color) {
        g.setFont(fontBold);
        int w = g.getFontMetrics().stringWidth(text) + 16;
        g.setColor(withAlpha(color, 40));
        g.fillRoundRect(x, 13, w, 22, 11, 11);
        g.setColor(color);
        g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(x, 13, w, 22, 11, 11);
        g.drawString(text, x + 8, 29);
        return x + w + 8;
    }

    private void drawFeed(Graphics2D g) {
        g.setFont(fontBold);
        FontMetrics fm = g.getFontMetrics();
        int y = HUD_HEIGHT + 14;
        for (FeedLine line : feed) {
            double age = time - line.createdAt();
            int alpha = (int) (255 * Math.max(0, Math.min(1, 6 - age))); // последняя секунда — плавно исчезает
            int width = fm.stringWidth(line.left()) + fm.stringWidth(line.middle()) + fm.stringWidth(line.right());
            g.setColor(new Color(5, 7, 13, alpha * 150 / 255));
            g.fillRoundRect(12, y, width + 16, 22, 10, 10);
            int x = 20;
            g.setColor(withAlpha(line.leftColor(), alpha));
            g.drawString(line.left(), x, y + 16);
            x += fm.stringWidth(line.left());
            g.setColor(withAlpha(TEXT_DIM, alpha));
            g.drawString(line.middle(), x, y + 16);
            x += fm.stringWidth(line.middle());
            g.setColor(withAlpha(line.rightColor(), alpha));
            g.drawString(line.right(), x, y + 16);
            y += 26;
        }
    }

    private void drawHelp(Graphics2D g) {
        if (time > 12) return;
        int alpha = (int) (255 * Math.min(1, 12 - time));
        String text = "WASD — движение   •   мышь — прицел   •   ЛКМ / пробел — огонь   •   Tab — таблица   •   Esc — выход";
        g.setFont(fontSmall);
        int w = g.getFontMetrics().stringWidth(text) + 28;
        int x = (viewWidth - w) / 2;
        int y = viewHeight - 32;
        g.setColor(new Color(5, 7, 13, alpha * 200 / 255));
        g.fillRoundRect(x, y - 7, w, 26, 13, 13);
        g.setColor(withAlpha(TEXT, alpha));
        g.drawString(text, x + 14, y + 11);
    }

    private void drawHitFlash(Graphics2D g) {
        if (hitFlash <= 0) return;
        g.setColor(withAlpha(DANGER, (int) (130 * hitFlash / 0.35)));
        g.setStroke(new BasicStroke(30f));
        g.drawRect(0, HUD_HEIGHT, viewWidth, viewHeight - HUD_HEIGHT);
    }

    private void drawDeathScreen(Graphics2D g, PlayerState me) {
        g.setColor(new Color(5, 7, 13, 150));
        g.fillRect(0, HUD_HEIGHT, viewWidth, viewHeight - HUD_HEIGHT);
        g.setFont(fontBig);
        drawCentered(g, "ВАС СБИЛИ", viewWidth / 2.0, viewHeight / 2.0, DANGER);
        g.setFont(fontMedium);
        String subtitle = (lastKiller != null ? "Стрелок: " + lastKiller + "   •   " : "")
                + String.format("Возрождение через %.1f с", me.respawnIn());
        drawCentered(g, subtitle, viewWidth / 2.0, viewHeight / 2.0 + 38, TEXT);
    }

    private void drawScoreboard(Graphics2D g, Snapshot view) {
        List<PlayerState> players = sortedByScore(view);
        int w = 520;
        int h = 84 + players.size() * 34;
        int x = (viewWidth - w) / 2;
        int y = (viewHeight - h) / 2;
        g.setColor(new Color(10, 14, 26, 235));
        g.fillRoundRect(x, y, w, h, 18, 18);
        g.setColor(WALL_EDGE);
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(x, y, w, h, 18, 18);

        g.setFont(fontMedium);
        drawCentered(g, "Таблица счёта", x + w / 2.0, y + 32, TEXT);
        g.setFont(fontBold);
        g.setColor(TEXT_DIM);
        g.drawString("Игрок", x + 28, y + 62);
        drawRight(g, "Сбито", x + 380, y + 62, TEXT_DIM);
        drawRight(g, "Смертей", x + w - 28, y + 62, TEXT_DIM);

        int rowY = y + 94;
        for (PlayerState p : players) {
            boolean isMe = p.id() == client.playerId();
            Color c = color(p.color());
            if (isMe) {
                g.setColor(withAlpha(c, 30));
                g.fillRoundRect(x + 14, rowY - 21, w - 28, 30, 10, 10);
            }
            g.setColor(c);
            g.fill(circle(x + 34, rowY - 6, 6));
            g.setFont(isMe ? fontBold : fontSmall);
            g.setColor(TEXT);
            String name = p.name() + (p.has(PlayerState.FLAG_BOT) ? "  [бот]" : "") + (isMe ? "  (вы)" : "");
            g.drawString(name, x + 50, rowY);
            drawRight(g, String.valueOf(p.score()), x + 380, rowY, TEXT);
            drawRight(g, String.valueOf(p.deaths()), x + w - 28, rowY, TEXT_DIM);
            rowY += 34;
        }
    }

    private void drawMessage(Graphics2D g, String title, String subtitle) {
        g.setColor(new Color(5, 7, 13, 200));
        g.fillRect(0, 0, viewWidth, viewHeight);
        g.setFont(fontMedium.deriveFont(28f));
        drawCentered(g, title, viewWidth / 2.0, viewHeight / 2.0, TEXT);
        g.setFont(fontSmall.deriveFont(16f));
        drawCentered(g, subtitle, viewWidth / 2.0, viewHeight / 2.0 + 34, TEXT_DIM);
    }

    // ------------------------------------------------------------------ помощники

    private static Shape createShip() {
        Path2D.Double ship = new Path2D.Double();
        ship.moveTo(21, 0);
        ship.lineTo(-13, 13);
        ship.lineTo(-7, 0);
        ship.lineTo(-13, -13);
        ship.closePath();
        return ship;
    }

    private static void glow(Graphics2D g, double x, double y, double radius, Color c, int alpha) {
        Color inner = withAlpha(c, alpha);
        Color outer = withAlpha(c, 0);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(x, y), (float) radius,
                new float[]{0f, 1f}, new Color[]{inner, outer}));
        g.fill(circle(x, y, radius));
    }

    private static Ellipse2D circle(double x, double y, double r) {
        return new Ellipse2D.Double(x - r, y - r, r * 2, r * 2);
    }

    /** Текст по центру с лёгкой тенью, чтобы читался на любом фоне. */
    private static void drawCentered(Graphics2D g, String text, double centerX, double baselineY, Color color) {
        float x = (float) (centerX - g.getFontMetrics().stringWidth(text) / 2.0);
        g.setColor(new Color(0, 0, 0, 170));
        g.drawString(text, x + 1, (float) baselineY + 1);
        g.setColor(color);
        g.drawString(text, x, (float) baselineY);
    }

    private static void drawRight(Graphics2D g, String text, double rightX, double baselineY, Color color) {
        g.setColor(color);
        g.drawString(text, (float) (rightX - g.getFontMetrics().stringWidth(text)), (float) baselineY);
    }

    private static List<PlayerState> sortedByScore(Snapshot view) {
        return view.players().stream()
                .sorted(Comparator.comparingInt(PlayerState::score).reversed()
                        .thenComparingInt(PlayerState::deaths))
                .toList();
    }

    private static Color color(int index) {
        return PLAYER_COLORS[Math.floorMod(index, PLAYER_COLORS.length)];
    }

    private static Color colorOf(Snapshot snapshot, int playerId) {
        PlayerState p = snapshot == null ? null : snapshot.player(playerId);
        return p == null ? TEXT_DIM : color(p.color());
    }

    private static String nameOf(Snapshot snapshot, int playerId) {
        PlayerState p = snapshot == null ? null : snapshot.player(playerId);
        return p == null ? "Кто-то" : p.name();
    }

    private static Color pickupColor(PickupType type) {
        return switch (type) {
            case HEALTH -> new Color(0x4DFF88);
            case RAPID_FIRE -> new Color(0xFFE14D);
            case SPEED -> new Color(0x4DA6FF);
        };
    }

    private static Color hpColor(double fraction) {
        if (fraction > 0.6) return new Color(0x4DFF88);
        if (fraction > 0.3) return new Color(0xFFE14D);
        return DANGER;
    }

    private static Color withAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    private record EdgePass(float width, Color color) {}

    private record FeedLine(String left, Color leftColor, String middle, String right, Color rightColor,
                            double createdAt) {}

    private static final class Particle {
        double x;
        double y;
        double vx;
        double vy;
        double life;
        final double maxLife;
        final double size;
        final Color color;

        Particle(double x, double y, double vx, double vy, double life, double size, Color color) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.life = life;
            this.maxLife = life;
            this.size = size;
            this.color = color;
        }
    }
}
