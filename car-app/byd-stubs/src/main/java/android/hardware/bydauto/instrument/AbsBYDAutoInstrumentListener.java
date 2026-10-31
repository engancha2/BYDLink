package android.hardware.bydauto.instrument;

public abstract class AbsBYDAutoInstrumentListener {
    public void onStatusChanged(int what, int value) {}
    public void onSafetyBeltStatusChanged(int position, int status) {}
}
