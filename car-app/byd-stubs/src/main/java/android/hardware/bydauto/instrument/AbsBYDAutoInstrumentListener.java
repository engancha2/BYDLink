package android.hardware.bydauto.instrument;

import android.hardware.bydauto.BYDAutoEventValue;

public abstract class AbsBYDAutoInstrumentListener {
    public void onStatusChanged(int what, int value) {}
    public void onSafetyBeltStatusChanged(int position, int status) {}
    /** Evento genérico por feature id (p. ej. INSTRUMENT_INPUT_KEYCODE_STATUS, los botones). Visto en AutoVideo del Surf. */
    public void onDataEventChanged(int eventType, BYDAutoEventValue value) {}
}
