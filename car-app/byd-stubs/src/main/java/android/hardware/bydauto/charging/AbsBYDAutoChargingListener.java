package android.hardware.bydauto.charging;

public abstract class AbsBYDAutoChargingListener {
    public void onChargerStateChanged(int state) {}
    public void onChargingPowerChanged(int power) {}
}
