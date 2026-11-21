package arena.server;

final class Bullet {
    final int id;
    final int owner;
    final double vx;
    final double vy;
    double x;
    double y;
    double life;

    Bullet(int id, int owner, double x, double y, double vx, double vy, double life) {
        this.id = id;
        this.owner = owner;
        this.x = x;
        this.y = y;
        this.vx = vx;
        this.vy = vy;
        this.life = life;
    }
}
