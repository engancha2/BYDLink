package android.hardware.bydauto.gearbox;

import android.content.Context;

/** Caja de cambios / marcha actual. */
public class BYDAutoGearboxDevice {
    public static BYDAutoGearboxDevice getInstance(Context ctx) { return new BYDAutoGearboxDevice(); }
    /** 0=P,1=R,2=N,3=D (a confirmar). */
    public int getGearboxAutoModeType() { return 0; }
    public void registerListener(AbsBYDAutoGearboxListener l) {}
    public void unregisterListener(AbsBYDAutoGearboxListener l) {}
}
