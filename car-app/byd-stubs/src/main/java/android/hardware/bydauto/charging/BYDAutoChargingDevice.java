package android.hardware.bydauto.charging;

import android.content.Context;

/** Carga / batería. Métodos vistos en libelectropkg.so (firmas por confirmar). */
public class BYDAutoChargingDevice {
    public static BYDAutoChargingDevice getInstance(Context ctx) { return new BYDAutoChargingDevice(); }
    /** Estado de carga: % batería (SOC). TODO confirmar nombre real. */
    public int getBatterySoc() { return 0; }
    public int getBatteryPowerVoltage() { return 0; }
    /** Minutos restantes de carga. */
    public int getChargingRestTime() { return 0; }
    /** ¿Enchufado/cargando? */
    public boolean isCharging() { return false; }
    public void registerListener(AbsBYDAutoChargingListener l) {}
    public void unregisterListener(AbsBYDAutoChargingListener l) {}
}
