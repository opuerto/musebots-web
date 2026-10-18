package dev.musebots.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportFormatterTest {

    private static Evaluation.Report report() {
        Map<String, Evaluation.Stat> scalars = new LinkedHashMap<>();
        scalars.put("notes per bar", new Evaluation.Stat(7.5, 1.25));
        scalars.put("repeated figures", new Evaluation.Stat(Double.NaN, Double.NaN));
        Map<String, Double> divergence = new LinkedHashMap<>();
        divergence.put("leaps", 0.1);
        divergence.put("gaps between notes", 0.3);
        return new Evaluation.Report(List.of(
                new Evaluation.Row("real solos (held out)", 52, scalars, Map.of("leaps", 0.0, "gaps between notes", 0.0), 0.26),
                new Evaluation.Row("rules", 156, scalars, divergence, 0.5)), 52, 200);
    }

    @Test
    void writesTheThreeTablesWithOneRowPerSystem() {
        String markdown = ReportFormatter.markdown(report());
        assertTrue(markdown.startsWith("# Solo evaluation"));
        assertTrue(markdown.contains("## How the solos are built"));
        assertTrue(markdown.contains("## How far from the real solos"));
        assertTrue(markdown.contains("## Copying"));
        assertEquals(3, markdown.lines().filter(line -> line.startsWith("| rules ")).count());
        assertTrue(markdown.contains("52 held-out solos, on tunes it was never trained on"));
        assertTrue(markdown.contains("learned from 200 solos"));
    }

    @Test
    void speaksOfOneSoloInTheSingular() {
        Evaluation.Report one = new Evaluation.Report(report().rows(), 1, 200);
        assertTrue(ReportFormatter.markdown(one).contains("of 1 held-out solo, on tunes it was never trained on"));
    }

    @Test
    void numbersAreRoundedAndMissingOnesAreADash() {
        String markdown = ReportFormatter.markdown(report());
        assertTrue(markdown.contains("| rules | 156 | 7.50 (1.25) | – (–) |"));
        assertTrue(markdown.contains("| rules | 0.10 | 0.30 | **0.20** |"));
        assertTrue(markdown.contains("| rules | 0.50 |"));
    }

    @Test
    void usesADecimalPointWhateverTheComputersLanguageIs() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("da-DK")); // decimal comma
            String markdown = ReportFormatter.markdown(report());
            assertTrue(markdown.contains("7.50 (1.25)"));
            assertFalse(markdown.contains("7,50"));
        } finally {
            Locale.setDefault(original);
        }
    }
}
