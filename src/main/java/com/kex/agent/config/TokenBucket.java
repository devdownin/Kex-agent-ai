package com.kex.agent.config;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Seau à jetons sans dépendance ni tâche de fond : le remplissage est calculé à la lecture, à
 * partir du temps écoulé. L'état tient dans un seul {@code long} mis à jour en compare-and-set,
 * ce qui suffit pour un compteur d'instance et évite un verrou sur le chemin chaud.
 *
 * <p>Les jetons sont comptés en millionièmes : un débit lent produirait sinon un incrément entier
 * nul à chaque lecture, et le seau ne se remplirait jamais.
 */
class TokenBucket {

    private static final long SCALE = 1_000_000L;
    private static final long NANOS_PER_MINUTE = Duration.ofMinutes(1).toNanos();

    private final long capacity;
    private final long refillPerMinute;
    private final long nanosToFill;
    private final AtomicLong tokens;
    private final AtomicLong lastRefillNanos;

    TokenBucket(int capacity, int refillPerMinute) {
        this.capacity = capacity * SCALE;
        this.refillPerMinute = refillPerMinute * SCALE;
        // Borne l'écoulement pris en compte : au-delà le seau est plein de toute façon, et le
        // produit elapsed * refillPerMinute déborderait après quelques heures d'inactivité.
        this.nanosToFill = this.capacity * NANOS_PER_MINUTE / this.refillPerMinute;
        this.tokens = new AtomicLong(this.capacity);
        this.lastRefillNanos = new AtomicLong(System.nanoTime());
    }

    boolean tryConsume() {
        refill();
        while (true) {
            long available = tokens.get();
            if (available < SCALE) {
                return false;
            }
            if (tokens.compareAndSet(available, available - SCALE)) {
                return true;
            }
        }
    }

    private void refill() {
        long now = System.nanoTime();
        long last = lastRefillNanos.get();
        long elapsed = Math.min(now - last, nanosToFill);
        if (elapsed <= 0 || !lastRefillNanos.compareAndSet(last, now)) {
            return;
        }
        long added = elapsed * refillPerMinute / NANOS_PER_MINUTE;
        tokens.updateAndGet(available -> Math.min(capacity, available + added));
    }
}
