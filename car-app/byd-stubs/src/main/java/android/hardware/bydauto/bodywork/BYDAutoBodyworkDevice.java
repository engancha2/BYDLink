package android.hardware.bydauto.bodywork;

import android.content.Context;

/** Carrocería: puertas, cierre, cinturones. */
public class BYDAutoBodyworkDevice {
    public static BYDAutoBodyworkDevice getInstance(Context ctx) { return new BYDAutoBodyworkDevice(); }
    /** Cerrar/abrir el coche. TODO confirmar firma (¿parámetro?). */
    public void lockDevice(boolean locked) {}
    public boolean isLocked() { return true; }
    /** Estado de puertas (bitmask o por índice). TODO confirmar. */
    public int getDoorState() { return 0; }
    public int getSafetyBeltStatus(int position) { return 0; }
    public void registerListener(AbsBYDAutoBodyworkListener l) {}
    public void unregisterListener(AbsBYDAutoBodyworkListener l) {}
}
