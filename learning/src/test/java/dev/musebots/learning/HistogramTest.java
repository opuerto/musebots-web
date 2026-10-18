package dev.musebots.learning;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class HistogramTest {

    private static Histogram of(int... bins) {
        Histogram histogram = new Histogram(4);
        for (int bin : bins) {
            histogram.add(bin);
        }
        return histogram;
    }

    @Test
    void sharesAddUpToOne() {
        assertArrayEquals(new double[] {0.5, 0.25, 0.25, 0}, of(0, 0, 1, 2).shares(), 1e-12);
        assertEquals(4, of(0, 0, 1, 2).total());
    }

    @Test
    void anEmptyHistogramHasNoShares() {
        assertArrayEquals(new double[4], new Histogram(4).shares(), 0);
    }

    @Test
    void identicalDistributionsAreZeroApart() {
        assertEquals(0, Histogram.divergence(of(0, 1, 1, 3), of(0, 1, 1, 3)), 1e-12);
        assertEquals(0, Histogram.divergence(of(0, 1), of(0, 0, 1, 1)), 1e-12, "only the shares matter, not the counts");
    }

    @Test
    void distributionsWithNothingInCommonAreOneBitApart() {
        assertEquals(1.0, Histogram.divergence(of(0, 0), of(3, 3)), 1e-12);
    }

    @Test
    void matchesAWorkedExample() {
        // P = (1, 0), Q = (1/2, 1/2): M = (3/4, 1/4); JSD = 1/2 log2(4/3) + 1/4 log2(2/3) + 1/4 log2(2) = 0.3113.
        assertEquals(0.3113, Histogram.divergence(of(0, 0), of(0, 1)), 1e-3);
    }

    @Test
    void isSymmetricAndNeverNegativeOrAboveOne() {
        Histogram a = of(0, 0, 0, 1, 2);
        Histogram b = of(1, 2, 2, 3, 3, 3);
        assertEquals(Histogram.divergence(a, b), Histogram.divergence(b, a), 1e-12);
        assertEquals(true, Histogram.divergence(a, b) > 0 && Histogram.divergence(a, b) <= 1);
    }

    @Test
    void twoEmptyHistogramsAreNotApart() {
        assertEquals(0, Histogram.divergence(new Histogram(4), new Histogram(4)), 0);
    }

    @Test
    void histogramsOfDifferentSizesCannotBeCompared() {
        assertThrows(IllegalArgumentException.class, () -> Histogram.divergence(new Histogram(4), new Histogram(5)));
    }
}
