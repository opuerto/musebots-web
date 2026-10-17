package dev.musebots.core;

/** The instruments an ensemble can contain. {@code gmProgram} is the General MIDI program number. */
public enum Instrument {
    PIANO(0),
    BASS(32),
    DRUMS(0),
    SAX(65);

    private final int gmProgram;

    Instrument(int gmProgram) {
        this.gmProgram = gmProgram;
    }

    public int gmProgram() {
        return gmProgram;
    }
}
