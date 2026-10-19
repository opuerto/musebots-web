package dev.musebots.learning;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Writes an evaluation as Markdown, so it can go straight into notes or a paper. */
public final class ReportFormatter {

    private ReportFormatter() {
    }

    public static String markdown(Evaluation.Report report) {
        StringBuilder out = new StringBuilder();
        out.append("# Solo evaluation\n\n");
        out.append(String.format(Locale.ROOT,
                "Each system soloed over the chord changes of %d held-out %s, on tunes it was never trained on (the model learned from %d solos). "
                        + "The first row is those real solos, which is what the others are measured against.%n%n",
                report.heldOutSolos(), report.heldOutSolos() == 1 ? "solo" : "solos", report.trainingSolos()));

        List<Evaluation.Row> rows = report.rows();
        Map<String, Evaluation.Stat> first = rows.get(0).scalars();

        out.append("## How the solos are built\n\nMean over solos, with the standard deviation in brackets.\n\n");
        out.append("| system | solos |");
        first.keySet().forEach(name -> out.append(' ').append(name).append(" |"));
        out.append("\n|---|---:|").append("---:|".repeat(first.size())).append('\n');
        for (Evaluation.Row row : rows) {
            out.append("| ").append(row.name()).append(" | ").append(row.solos()).append(" |");
            for (Evaluation.Stat stat : row.scalars().values()) {
                out.append(' ').append(number(stat.mean())).append(" (").append(number(stat.sd())).append(") |");
            }
            out.append('\n');
        }

        out.append("\n## How far from the real solos\n\n"
                + "Jensen-Shannon divergence of the distributions pooled over all solos, in bits: 0 means the same distribution as "
                + "the real solos, 1 means nothing in common. Lower is closer.\n\n");
        out.append("| system |");
        Map<String, Double> divergences = rows.get(0).divergence();
        divergences.keySet().forEach(name -> out.append(' ').append(name).append(" |"));
        out.append(" **mean** |\n|---|").append("---:|".repeat(divergences.size() + 1)).append('\n');
        for (Evaluation.Row row : rows) {
            out.append("| ").append(row.name()).append(" |");
            row.divergence().values().forEach(value -> out.append(' ').append(number(value)).append(" |"));
            out.append(" **").append(number(row.meanDivergence())).append("** |\n");
        }

        out.append("\n## Copying\n\n"
                + "The share of six-note runs (by their leaps) that also occur in the training solos. Real solos on new tunes copy a "
                + "little too, because jazz has stock phrases, so read a system against the real solos' own rate. "
                + "A system far above it is repeating what it was taught.\n\n| system | copy rate |\n|---|---:|\n");
        for (Evaluation.Row row : rows) {
            out.append("| ").append(row.name()).append(" | ").append(number(row.copyRate())).append(" |\n");
        }
        return out.toString();
    }

    /** How well models predict the held-out solos, one row per model. */
    public static String surprise(Map<String, MarkovModel.Surprise> models) {
        StringBuilder out = new StringBuilder("\n## How well the model predicts real solos\n\n"
                + "How surprised a model is by each note of the held-out solos, in bits (lower is better): how likely it thought "
                + "the note's rhythm and its leap were, in the situation the player was in. This ranks what models have learned, "
                + "which the statistics above cannot, since a counting model reproduces those by construction. A baseline is "
                + "scored as models were played before: only the most detailed situation seen often enough, and no memory of the solo.\n\n"
                + "| model | rhythm | pitch | total | notes |\n|---|---:|---:|---:|---:|\n");
        models.forEach((name, s) -> out.append("| ").append(name).append(" | ").append(number(s.rhythmBits())).append(" | ")
                .append(number(s.pitchBits())).append(" | ").append(number(s.bits())).append(" | ").append(s.notes()).append(" |\n"));
        return out.toString();
    }

    static String number(double value) {
        return Double.isNaN(value) ? "–" : String.format(Locale.ROOT, "%.2f", value);
    }
}
