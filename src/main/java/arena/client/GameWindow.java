package arena.client;

import arena.common.Snapshot;
import arena.common.Timing;
import arena.server.GameServer;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferStrategy;

/**
 * Окно игры. Рисуем сами в отдельном потоке (активный рендеринг через BufferStrategy),
 * а не через repaint() — так кадры идут ровно и без мерцания.
 */
public final class GameWindow {
    private static final long FRAME_NANOS = 1_000_000_000L / 120; // не больше 120 кадров в секунду

    private final GameClient client;
    private final GameServer server; // не null, если игру создали мы (мы — хост)
    private final Renderer renderer;
    private final InputHandler input = new InputHandler();
    private volatile boolean running = true;
    private JFrame frame;
    private Canvas canvas;

    public GameWindow(GameClient client, GameServer server, String hostInfo) {
        this.client = client;
        this.server = server;
        this.renderer = new Renderer(client, hostInfo);
    }

    /** Вызывать из потока Swing (EDT). */
    public void show() {
        frame = new JFrame("Неоновая арена");
        canvas = new Canvas();
        canvas.setPreferredSize(initialSize());
        canvas.setBackground(Color.BLACK);
        canvas.setIgnoreRepaint(true);
        canvas.setFocusTraversalKeysEnabled(false); // иначе Tab будет переключать фокус, а не показывать счёт
        input.attach(canvas);

        frame.add(canvas);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                exit();
            }
        });
        frame.setVisible(true);
        canvas.createBufferStrategy(2);
        canvas.requestFocusInWindow();

        Thread loop = new Thread(this::renderLoop, "arena-render");
        loop.setDaemon(true);
        loop.start();
    }

    /** Окно в «родном» размере 1280×768, но не больше экрана. */
    private Dimension initialSize() {
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        double k = Math.min(1.0, Math.min((screen.width - 40) / (double) renderer.viewWidth(),
                (screen.height - 60) / (double) renderer.viewHeight()));
        return new Dimension((int) (renderer.viewWidth() * k), (int) (renderer.viewHeight() * k));
    }

    private void renderLoop() {
        BufferStrategy strategy = canvas.getBufferStrategy();
        long last = System.nanoTime();
        long nextFrame = last;
        while (running) {
            long now = System.nanoTime();
            double dt = Math.min(0.1, (now - last) / 1e9);
            last = now;

            Snapshot view = client.snapshots().sample(now);
            client.setInput(input.buttons(), renderer.aimAngle(view, input.mouseX(), input.mouseY()));

            try {
                do {
                    do {
                        Graphics2D g = (Graphics2D) strategy.getDrawGraphics();
                        try {
                            renderer.render(g, canvas.getWidth(), canvas.getHeight(), view, dt,
                                    input.isHeld(KeyEvent.VK_TAB));
                        } finally {
                            g.dispose();
                        }
                    } while (strategy.contentsRestored());
                    strategy.show();
                } while (strategy.contentsLost());
                Toolkit.getDefaultToolkit().sync();
            } catch (IllegalStateException e) {
                if (!running) break; // окно уже закрывается — буфер уничтожен
            }

            if (input.consumePress(KeyEvent.VK_ESCAPE)) SwingUtilities.invokeLater(this::exit);

            nextFrame += FRAME_NANOS;
            if (System.nanoTime() - nextFrame > FRAME_NANOS * 5) nextFrame = System.nanoTime();
            Timing.sleepUntil(nextFrame);
        }
    }

    private void exit() {
        if (!running) return;
        running = false;
        client.close();
        if (server != null) server.close();
        frame.dispose();
        System.exit(0);
    }
}
