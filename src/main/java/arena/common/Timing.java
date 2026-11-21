package arena.common;

/** Помощник для игровых циклов, которым нужно ждать до определённого момента. */
public final class Timing {
    private Timing() {}

    /**
     * Спит до момента deadline (в единицах System.nanoTime()).
     * Точность около 1 мс — для игры этого хватает, а процессор не греется впустую.
     */
    public static void sleepUntil(long deadline) {
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 1_000_000) {
            try {
                Thread.sleep(Math.max(1, Math.min(remaining / 1_000_000 - 1, 10)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
