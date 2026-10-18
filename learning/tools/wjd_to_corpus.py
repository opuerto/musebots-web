#!/usr/bin/env python3
"""Converts the Weimar Jazz Database (SQLite) into the plain JSON corpus the Java tools read.

    python3 wjd_to_corpus.py wjazzd.db corpus.json

The Weimar Jazz Database (https://jazzomat.hfm-weimar.de/) holds transcribed jazz solos and is released under the Open
Data Commons Open Database License (ODbL): credit it, and share any database you publish that is derived from it
under the same terms. This script does not interpret the music; it only copies it into a simpler shape:

    {
      "source": "...", "ticksPerBeat": 12,
      "solos": [{
        "id": "wjd-1", "performer": "...", "title": "...", "instrument": "as", "style": "BEBOP", "rhythmFeel": "SWING",
        "key": "Bb-maj", "tempoBpm": 218.8, "beatsPerBar": 4,
        "chords": [{"tick": 0, "symbol": "Bb6"}, ...],          # where the chord changes, in the database's own notation
        "notes":  [{"tick": 3, "duration": 4, "pitch": 65}, ...] # onset and length in ticks, MIDI pitch
      }]
    }

A tick is a twelfth of a beat, counted from the first beat of the first bar of the solo (a pick-up before it is negative).
Twelve divides evenly into the straight eighths, triplets and sixteenths the solos are written in. Use only data you are
allowed to use: this file is derived from the database and carries its licence.
"""
import json
import sqlite3
import sys

TICKS_PER_BEAT = 12
SOURCE = "Weimar Jazz Database v2.1 (ODbL), https://jazzomat.hfm-weimar.de/"


def beats_per_bar(signature):
    """The number of beats in a bar from a signature such as '4/4' (None if unknown or not a simple one)."""
    try:
        numerator, denominator = signature.split("/")
        return int(numerator) if int(denominator) == 4 else None
    except (AttributeError, ValueError):
        return None


def tick_of(bar, beat, tatum, division, bpb):
    """The tick of a position written as bar, beat (from 1) and the tatum (from 1) of the beat's `division` parts."""
    position = (beat - 1) + (tatum - 1) / division
    return bar * bpb * TICKS_PER_BEAT + round(position * TICKS_PER_BEAT)


def convert(connection):
    """Returns (corpus, skipped): the corpus dict, and a count of solos left out for each reason."""
    cursor = connection.cursor()
    skipped = {"unknown or unusual time signature": 0, "no notes": 0}
    solos = []

    rows = cursor.execute(
        "select melid, performer, title, instrument, style, rhythmfeel, key, avgtempo, signature "
        "from solo_info order by melid"
    ).fetchall()
    for melid, performer, title, instrument, style, feel, key, tempo, signature in rows:
        bpb = beats_per_bar(signature)
        if bpb is None:
            skipped["unknown or unusual time signature"] += 1
            continue

        notes = []
        for bar, beat, tatum, division, pitch, duration, beatdur in cursor.execute(
            "select bar, beat, tatum, division, pitch, duration, beatdur from melody "
            "where melid = ? order by onset, eventid", (melid,)
        ):
            if None in (bar, beat, tatum, division, pitch, duration) or not division:
                continue
            seconds_per_beat = beatdur if beatdur and beatdur > 0 else (60.0 / tempo if tempo else None)
            if not seconds_per_beat:
                continue
            notes.append({
                "tick": tick_of(bar, beat, tatum, division, bpb),
                "duration": max(1, round(TICKS_PER_BEAT * duration / seconds_per_beat)),
                "pitch": int(round(pitch)),
            })
        if not notes:
            skipped["no notes"] += 1
            continue

        # The database writes a chord only on the beat where it starts.
        chords = [
            {"tick": bar * bpb * TICKS_PER_BEAT + (beat - 1) * TICKS_PER_BEAT, "symbol": chord}
            for bar, beat, chord in cursor.execute(
                "select bar, beat, chord from beats where melid = ? and chord is not null and chord != '' "
                "order by onset, beatid", (melid,)
            )
        ]

        solos.append({
            "id": f"wjd-{melid}", "performer": performer, "title": title, "instrument": instrument,
            "style": style, "rhythmFeel": feel, "key": key, "tempoBpm": tempo, "beatsPerBar": bpb,
            "chords": chords, "notes": notes,
        })

    return {"source": SOURCE, "ticksPerBeat": TICKS_PER_BEAT, "solos": solos}, skipped


def main(argv):
    if len(argv) != 3:
        print(__doc__)
        return 2
    database, output = argv[1], argv[2]
    connection = sqlite3.connect(f"file:{database}?mode=ro", uri=True)  # read-only: it is someone else's data
    corpus, skipped = convert(connection)
    with open(output, "w") as out:
        json.dump(corpus, out, separators=(",", ":"))
    notes = sum(len(solo["notes"]) for solo in corpus["solos"])
    print(f"wrote {len(corpus['solos'])} solos and {notes} notes to {output}")
    for reason, count in skipped.items():
        if count:
            print(f"  left out {count} solos: {reason}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
