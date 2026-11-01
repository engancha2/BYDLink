package android.hardware.bydauto.instrument;

import android.content.Context;

/** Instrumentación: confort de asientos (calefacción/ventilación) y cinturones. */
public class BYDAutoInstrumentDevice {
    public static BYDAutoInstrumentDevice getInstance(Context ctx) { return new BYDAutoInstrumentDevice(); }
    /** position: 0=conductor, 1=pasajero. stage: 0..3 (a confirmar). */
    public void setSeatHeatingState(int position, int stage) {}
    public void setSeatVentilatingState(int position, int stage) {}
    public int getSafetyBeltStatus(int position) { return 0; }
    public void registerListener(AbsBYDAutoInstrumentListener l) {}
    /** Solo los feature ids pedidos (así lo usa AutoVideo del Surf para INSTRUMENT_INPUT_KEYCODE_STATUS). */
    public void registerListener(AbsBYDAutoInstrumentListener l, int[] featureIds) {}
    public void unregisterListener(AbsBYDAutoInstrumentListener l) {}
}
