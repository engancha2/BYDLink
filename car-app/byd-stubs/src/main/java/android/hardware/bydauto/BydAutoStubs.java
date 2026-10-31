package android.hardware.bydauto;

/*
 * Stubs mínimos del SDK de BYD, deducidos de las cadenas del APK de Electro
 * (libelectropkg.so). Firmas APROXIMADAS: hay que confirmarlas contra el
 * framework real del coche. Para extraer el real (mañana, con ADB):
 *
 *   adb pull /system/framework/framework.jar
 *   adb pull /system/framework/   (buscar bydauto / byd-*.jar)
 *   # o localizar el .jar que declara android.hardware.bydauto.*
 *
 * y reemplazar este módulo por ese jar como compileOnly.
 *
 * Posiciones de asiento (convención a confirmar): 0 = conductor, 1 = pasajero.
 * Etapas de confort: -2..2 (negativo ventilación, positivo calefacción).
 */

import android.content.Context;

public final class BydAutoStubs { private BydAutoStubs() {} }
