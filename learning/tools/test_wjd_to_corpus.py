import sqlite3
import unittest

import wjd_to_corpus as wjd


def database():
    """A tiny database with the columns the converter reads (the real one has many more)."""
    db = sqlite3.connect(":memory:")
    db.executescript("""
        create table solo_info (melid integer, performer text, title text, instrument text, style text, rhythmfeel text,
                                key text, avgtempo real, signature text);
        create table melody (eventid integer, melid integer, onset real, pitch real, duration real, bar integer,
                             beat integer, division integer, tatum integer, beatdur real);
        create table beats (beatid integer, melid integer, onset real, bar integer, beat integer, chord text);
    """)
    return db


class Convert(unittest.TestCase):
    def setUp(self):
        self.db = database()
        self.db.execute("insert into solo_info values (1, 'Art Pepper', 'Anthropology', 'cl', 'BEBOP', 'SWING', 'Bb-maj', 120.0, '4/4')")

    def note(self, eventid, onset, pitch, duration, bar, beat, division, tatum, beatdur=0.5, melid=1):
        self.db.execute("insert into melody values (?,?,?,?,?,?,?,?,?,?)",
                        (eventid, melid, onset, pitch, duration, bar, beat, division, tatum, beatdur))

    def test_ticks_are_twelfths_of_a_beat_counted_from_the_first_bar(self):
        self.note(1, 0.0, 65, 0.5, 0, 1, 1, 1)   # beat 1                           -> tick 0
        self.note(2, 0.5, 63, 0.25, 0, 2, 2, 2)  # the off-beat eighth of beat 2    -> 12 + 6
        self.note(3, 0.75, 60, 0.125, 0, 2, 4, 4)  # the last sixteenth of beat 2   -> 12 + 9
        self.note(4, 1.0, 58, 0.5, 1, 1, 3, 2)   # the middle triplet of bar 2     -> 48 + 4
        corpus, _ = wjd.convert(self.db)
        self.assertEqual([0, 18, 21, 52], [n["tick"] for n in corpus["solos"][0]["notes"]])

    def test_durations_are_measured_in_beats_of_that_moment(self):
        self.note(1, 0.0, 65, 0.5, 0, 1, 1, 1, beatdur=0.5)    # one beat        -> 12 ticks
        self.note(2, 0.5, 65, 0.25, 0, 2, 1, 1, beatdur=0.5)   # half a beat     -> 6
        self.note(3, 1.0, 65, 0.3, 0, 3, 1, 1, beatdur=0.3)    # a slower beat   -> 12
        corpus, _ = wjd.convert(self.db)
        self.assertEqual([12, 6, 12], [n["duration"] for n in corpus["solos"][0]["notes"]])

    def test_a_very_short_note_still_lasts_a_tick(self):
        self.note(1, 0.0, 65, 0.001, 0, 1, 1, 1)
        corpus, _ = wjd.convert(self.db)
        self.assertEqual(1, corpus["solos"][0]["notes"][0]["duration"])

    def test_a_pickup_before_the_first_bar_is_negative(self):
        self.note(1, 0.0, 65, 0.5, -1, 4, 1, 1)  # beat 4 of the bar before bar 0
        corpus, _ = wjd.convert(self.db)
        self.assertEqual(-12, corpus["solos"][0]["notes"][0]["tick"])

    def test_chords_are_kept_where_they_change_and_blanks_are_ignored(self):
        self.note(1, 0.0, 65, 0.5, 0, 1, 1, 1)
        for beatid, onset, bar, beat, chord in [(1, 0.0, 0, 1, "Bb6"), (2, 0.5, 0, 2, ""), (3, 1.0, 0, 3, "G7"), (4, 1.5, 0, 4, None), (5, 2.0, 1, 1, "C-7")]:
            self.db.execute("insert into beats values (?,?,?,?,?,?)", (beatid, 1, onset, bar, beat, chord))
        corpus, _ = wjd.convert(self.db)
        self.assertEqual([{"tick": 0, "symbol": "Bb6"}, {"tick": 24, "symbol": "G7"}, {"tick": 48, "symbol": "C-7"}],
                         corpus["solos"][0]["chords"])

    def test_the_description_of_the_solo_is_copied(self):
        self.note(1, 0.0, 65, 0.5, 0, 1, 1, 1)
        solo = wjd.convert(self.db)[0]["solos"][0]
        self.assertEqual(("wjd-1", "Art Pepper", "Anthropology", "cl", "BEBOP", "SWING", 120.0, 4),
                         (solo["id"], solo["performer"], solo["title"], solo["instrument"], solo["style"], solo["rhythmFeel"],
                          solo["tempoBpm"], solo["beatsPerBar"]))

    def test_solos_whose_beat_is_not_a_quarter_note_are_left_out_and_counted(self):
        # Quarter-note meters are kept with their bar length (the Java side chooses which it can use); 6/8 and a
        # missing signature cannot be placed on the grid, so those solos are left out.
        self.db.execute("insert into solo_info values (2, 'X', 'Waltz', 'as', '', '', '', 100.0, '3/4')")
        self.db.execute("insert into solo_info values (3, 'X', 'Odd', 'as', '', '', '', 100.0, '6/8')")
        self.db.execute("insert into solo_info values (4, 'X', 'Unknown', 'as', '', '', '', 100.0, '')")
        for melid in (2, 3, 4):
            self.note(10 + melid, 0.0, 60, 0.5, 0, 1, 1, 1, melid=melid)
        self.note(1, 0.0, 65, 0.5, 0, 1, 1, 1)
        corpus, skipped = wjd.convert(self.db)
        self.assertEqual(["wjd-1", "wjd-2"], [s["id"] for s in corpus["solos"]])
        self.assertEqual([4, 3], [s["beatsPerBar"] for s in corpus["solos"]])
        self.assertEqual(2, skipped["unknown or unusual time signature"])

    def test_notes_missing_their_position_are_skipped_and_an_empty_solo_is_left_out(self):
        self.note(1, 0.0, 65, 0.5, None, 1, 1, 1)
        corpus, skipped = wjd.convert(self.db)
        self.assertEqual([], corpus["solos"])
        self.assertEqual(1, skipped["no notes"])

    def test_beats_per_bar(self):
        self.assertEqual(4, wjd.beats_per_bar("4/4"))
        self.assertEqual(3, wjd.beats_per_bar("3/4"))
        self.assertIsNone(wjd.beats_per_bar("6/8"))
        self.assertIsNone(wjd.beats_per_bar(""))
        self.assertIsNone(wjd.beats_per_bar(None))


if __name__ == "__main__":
    unittest.main()
