package dev.musebots.learning;

/** Counts in a fixed number of bins, and how far apart two such distributions are. */
public final class Histogram {

    private final double[] counts;

    public Histogram(int bins) {
        this.counts = new double[bins];
    }

    public void add(int bin) {
        counts[bin]++;
    }

    public int bins() {
        return counts.length;
    }

    public double total() {
        double sum = 0;
        for (double count : counts) {
            sum += count;
        }
        return sum;
    }

    /** The share of the counts in each bin (all zero if there are none). */
    public double[] shares() {
        double total = total();
        double[] shares = new double[counts.length];
        if (total > 0) {
            for (int i = 0; i < counts.length; i++) {
                shares[i] = counts[i] / total;
            }
        }
        return shares;
    }

    /**
     * The Jensen-Shannon divergence between two distributions, in bits: 0 if they are the same, 1 if they share no
     * outcomes. Unlike plain KL divergence it is symmetric and defined when one distribution has a bin the other lacks.
     */
    public static double divergence(Histogram a, Histogram b) {
        if (a.bins() != b.bins()) {
            throw new IllegalArgumentException("histograms have different bins");
        }
        double[] p = a.shares();
        double[] q = b.shares();
        double sum = 0;
        for (int i = 0; i < p.length; i++) {
            double mean = (p[i] + q[i]) / 2;
            if (p[i] > 0) {
                sum += 0.5 * p[i] * log2(p[i] / mean);
            }
            if (q[i] > 0) {
                sum += 0.5 * q[i] * log2(q[i] / mean);
            }
        }
        return Math.max(0, sum);
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }
}
