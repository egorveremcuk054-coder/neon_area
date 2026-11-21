package arena.client;

import java.awt.Component;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static arena.common.Message.Input.*;

/**
 * Запоминает, какие клавиши зажаты и где мышь.
 * События приходят в потоке Swing, а читаются в потоке отрисовки, поэтому коллекции потокобезопасные.
 */
final class InputHandler {
    // Те же клавиши WASD, но в русской раскладке (Ц, Ы, Ф, В).
    private static final int RU_W = KeyEvent.getExtendedKeyCodeForChar('ц');
    private static final int RU_S = KeyEvent.getExtendedKeyCodeForChar('ы');
    private static final int RU_A = KeyEvent.getExtendedKeyCodeForChar('ф');
    private static final int RU_D = KeyEvent.getExtendedKeyCodeForChar('в');

    private final Set<Integer> held = ConcurrentHashMap.newKeySet();
    private final Set<Integer> pressed = ConcurrentHashMap.newKeySet();
    private volatile int mouseX;
    private volatile int mouseY;
    private volatile boolean mouseDown;

    void attach(Component component) {
        component.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                for (int code : codes(e)) {
                    if (held.add(code)) pressed.add(code);
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                for (int code : codes(e)) held.remove(code);
            }
        });

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                component.requestFocusInWindow();
                if (e.getButton() == MouseEvent.BUTTON1) mouseDown = true;
                track(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) mouseDown = false;
            }

            @Override
            public void mouseMoved(MouseEvent e) { track(e); }

            @Override
            public void mouseDragged(MouseEvent e) { track(e); }
        };
        component.addMouseListener(mouse);
        component.addMouseMotionListener(mouse);

        // Окно потеряло фокус (Alt+Tab) — отпускаем все клавиши, иначе корабль уедет сам.
        component.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                held.clear();
                mouseDown = false;
            }
        });
    }

    private void track(MouseEvent e) {
        mouseX = e.getX();
        mouseY = e.getY();
    }

    private static int[] codes(KeyEvent e) {
        return new int[]{e.getKeyCode(), e.getExtendedKeyCode()};
    }

    /** Кнопки в формате сетевого сообщения Input. */
    int buttons() {
        int result = 0;
        if (anyHeld(KeyEvent.VK_W, KeyEvent.VK_UP, RU_W)) result |= UP;
        if (anyHeld(KeyEvent.VK_S, KeyEvent.VK_DOWN, RU_S)) result |= DOWN;
        if (anyHeld(KeyEvent.VK_A, KeyEvent.VK_LEFT, RU_A)) result |= LEFT;
        if (anyHeld(KeyEvent.VK_D, KeyEvent.VK_RIGHT, RU_D)) result |= RIGHT;
        if (mouseDown || isHeld(KeyEvent.VK_SPACE)) result |= FIRE;
        return result;
    }

    boolean isHeld(int keyCode) {
        return held.contains(keyCode);
    }

    /** true один раз после нажатия клавиши (для действий вроде «выйти»). */
    boolean consumePress(int keyCode) {
        return pressed.remove(keyCode);
    }

    int mouseX() { return mouseX; }
    int mouseY() { return mouseY; }

    private boolean anyHeld(int... keyCodes) {
        for (int code : keyCodes) {
            if (held.contains(code)) return true;
        }
        return false;
    }
}
