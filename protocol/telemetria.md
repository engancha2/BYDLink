# Contrato de telemetría y mandos BYDLink (coche → móvil)

Coche: **BYD Dolphin Surf** (DiLink 3.0, Android 10). El coche publica en
`cars/{carId}/status` cada 2 s (ver `net/CarClient.publishState`) y ejecuta los comandos que
el móvil escribe en `cars/{carId}/commands`. Este documento fija TODOS los campos de `status`
y TODAS las acciones de `car/CarCommands.kt`.

Fuente de los datos: SDK `android.hardware.bydauto.*` leído por reflexión con
`BydPermissionContext` (salta permisos de firma GET/SET). Codificación de valores y centinelas
en `research/sdk-catalogo.md` y `research/apps-comunidad.md`.

**Regla de disponibilidad:** si un dato no se puede leer, se publica **`null`** (nunca el
centinela del SDK: 65535, 1048575, -10011, -10013, -1.0, etc.). En Firebase un `null` no
escribe la clave → el móvil la recibe como ausente/`null`.

Leyenda de estado: **[Surf]** confirmado en el sondeo real; **[conf]** confirmado por la
comunidad en familia Seagull/Dolphin; **[?]** por confirmar con el sondeo en este coche.

---

## 1. `status` — campos publicados

### Compatibilidad (el móvil ya los usa; mismo nombre y significado)

| Campo | Tipo | Unidad / valores | Fuente SDK | Estado |
|---|---|---|---|---|
| `ts` | Long | epoch ms | reloj del coche | — |
| `soc` | Int?\* | % 0-100 | `statistic.getSOCBatteryPercentage()` (fallback `getElecPercentageValue`, luego `bodywork.getBatteryCapacity()/2`) | [Surf] 70 |
| `charging` | Bool | cargando de verdad | `charging.getBatteryManagementDeviceState()==1` o potencia>0 | [Surf] |
| `chargingPower` | Int? | kW | `charging.getChargingPower()` | [Surf] |
| `chargeRestMin` | Int? | min | `charging.getChargingRestTime()` `[h,min]` | [Surf] |
| `gear` | String | `"P"`/`"R"`/`"N"`/`"D"` | `gearbox.getCurrentGear()` mapeo comunidad **3=P,1=R,0=N,2=D** | P [Surf]; R/N/D [?] |
| `speed` | Int | km/h | `speed.getCurrentSpeed()` | [Surf] |
| `locked` | Bool? | true=cerrado | `ota.getLFDoorLockState()`/OTA_LF_DOOR_LOCK (2=cerrado,1=abierto); fallback `bodywork.getAutoSystemState()>=1` | [?] |
| `doors` | obj | `{fl,fr,rl,rr,trunk,hood}` Bool? (true=abierta) | `bodywork.getDoorState(area)` 1=LF,2=RF,3=LR,4=RR,5=HOOD,6=LUGGAGE | [Surf] (con arg) |
| `windows` | obj | `{fl,fr,rl,rr,sunroof}` Bool (true=abierta) | `bodywork.getWindowState(area)` 1-4; techo `getSunroofState()` | [Surf] (con arg) |
| `tyres` | obj | `{fl,fr,rl,rr}` Double? **bar** | `tyre.getTyrePressureValue(area)` kPa/100; área 1=LF,2=RF,3=LR,4=RR | [Surf] (con arg); unidad [?] |
| `seatbelts` | obj | `{driver,passenger}` Bool? (true=puesto) | `safetybelt`/`instrument.getSafetyBeltStatus(area)` 1=puesto,0=suelto | [?] |
| `outTemp` | Int? | °C exterior | `instrument.getOutCarTemperature()` (fallback `ac.getTemprature(4)`) | [Surf] 21 |
| `tempUnit` | String | `"C"`/`"F"` | `ac.getTemperatureUnit()` 1=°C | [Surf] C |
| `odometer` | Int? | km | `statistic.getTotalMileageValue()` | [Surf] 15581 |
| `rangeKm` | Int? | km | `statistic.getElecDrivingRangeValue()` | [Surf] 227 |
| `power` | Int? | kW (+consume/-regenera) | feature `Engine.ENGINE_POWER` (fallback `engine.getEnginePower()`) | [?] |
| `consumptionKwh100` | Double? | kWh/100km (medio) | `statistic.getTotalElecConPHMValue()` | [Surf] 13.7 |
| `voltage` | Int? | V (pack HV) | feature `Charging.CHARGING_CHARGE_BATTERY_VOLT` | [?] |
| `location` | obj | `{lat,lng,heading,ts}` | GPS del head-unit (`LocationReader`) | [Surf] |

\* `soc` pasa a anulable: si el SDK no da SOC real, se publica `null` (antes podía salir 0). El
móvil ya hace `s.soc != null`. **Nota de contrato:** el móvil también lee `s.consumption` en un
sitio (`app.js` renderStatus); aquí se mantiene `consumptionKwh100` según la lista de
compatibilidad del encargo. Si se quiere que esa tarjeta se rellene, el móvil debería leer
`consumptionKwh100` (o publicar además un alias `consumption`; no se ha hecho para no duplicar).

### Nuevos campos (todos anulables; `null` si no disponible)

| Campo | Tipo | Unidad / valores | Fuente SDK | Estado |
|---|---|---|---|---|
| `powerState` | String? | `"OFF"/"ACC"/"ON"/"OK"/"FAKE_OK"` | `bodywork.getPowerLevel()` 0/1/2/3/4 | [Surf] ON |
| `gearRaw` | Int? | crudo de `getCurrentGear` (para calibrar R/N/D) | `gearbox.getCurrentGear()` | [Surf] 3 en P |
| `alarm` | Bool? | alarma antirrobo activada | `bodywork.getAlarmState()` (1=ON) | [Surf] |
| `bmsState` | Int? | 0 ready,1 charging,2 finish,3 discharg,9 schedule,15 idle | `charging.getBatteryManagementDeviceState()` | [Surf] 15 |
| `chargeLimit` | Int? | % límite de carga (15-70) | `setting.getSOCTarget()` (255→null) | [Surf] n/d |
| `chargingGun` | String? | `"none"/"AC"/"DC"/"AC_DC"/"V2L"` | `charging.getChargingGunState()` | [Surf] none |
| `soh` | Int? | % salud batería | feature `Statistic.STATISTIC_BATTERY_HEALTHY_INDEX` | [?] |
| `batteryTempMax` | Int? | °C celda más caliente (raw-40) | feature `Statistic.STATISTIC_HIGHEST_BATTERY_TEMP` | [?] |
| `batteryTempMin` | Int? | °C celda más fría (raw-40) | feature `Statistic.STATISTIC_LOWEST_BATTERY_TEMP` | [?] |
| `aux12v` | Double? | V batería auxiliar 12 V | `ota.getBatteryPowerVoltage()` / feature `Ota.OTA_BATTERY_POWER_VOLTAGE` | [?] |
| `inTemp` | Int? | °C cabina | `ac.getTemprature(0)` | [?] |
| `windowsPct` | obj? | `{fl,fr,rl,rr,sunroof}` Int? 0-100 % apertura | `bodywork.getWindowOpenPercent(area)` / `getSunroofPosition()` | [?] |
| `tyresState` | obj? | `{fl,fr,rl,rr}` Int? 0=normal,1=sobre,2=baja | `tyre.getTyrePressureState(area)` | [?] |
| `occupancy` | obj? | `{driver,passenger,rearLeft,rearMid,rearRight}` Bool? | `safetybelt.getPassengerStatus(area)` | [?] |
| `climate` | obj? | ver abajo | `ac.*` | [Surf] parcial |
| `seatComfort` | obj? | `{driver,passenger}` Int? -2..2 (neg=vent, pos=calef) | `setting.getSeatHeatingState/VentilatingState(pos)` | [?] |
| `lights` | obj? | `{lowBeam,highBeam,position,drl,rearFog,hazard}` Bool? | `light.getLightStatus(type)` / `getDayTimeLightState` / `getDoubleFlashLightState` | [Surf] parcial |

`climate` = `{ on:Bool?, temp:Int?(°C), fan:Int?(0-7), mode:Int?(1 cara…5 parabrisas),
recirc:Bool?, defrostFront:Bool?, defrostRear:Bool?, maxCooling:Bool?, ventilation:Bool?,
auto:Bool? }`.

**Rendimiento:** las lecturas lentas del BMS (`soh`, `batteryTempMax/Min`, `voltage`, `power`,
`aux12v`) se refrescan 1 de cada 5 ciclos (~10 s) y se cachean; el resto va en cada ciclo (2 s).
Cada lectura está protegida con try/catch: un fallo no tumba las demás.

---

## 2. Acciones — `CarCommands.handle(action, payload)`

El coche responde en `responses/{cmdId}` con `{ ok, data, ts }`. `data` es lo que devuelve
`handle` (mapa de resultado). Si falla, `ok=false` y `data.error` lleva el mensaje en español.
Cada setter **lee de vuelta** el estado y devuelve `applied` (el SDK devuelve 0 aunque ignore la
orden, así que `applied` refleja la lectura real cuando se puede).

**Seguridad (se aplica en el coche, no en el móvil):** los mandos que mueven asientos o abren
puertas/maletero/ventanas/techo, y `powerOff`, se BLOQUEAN si el coche no está parado
(velocidad desconocida = bloqueado, *fail-closed*). Cerrar ventanas/maletero sí se permite.

| Acción | Payload | Efecto | Respuesta `data` | Estado |
|---|---|---|---|---|
| `setLock` | `{locked:Bool}` | cerrar/abrir puertas: feature `BODYWORK_FOUR_DOOR_SET` (1 unlock/2 lock) → autoservice OP_LOCK/UNLOCK → `lockDevice` | `{applied,locked}` | [conf] |
| `lock` / `unlock` | — | atajo de `setLock` | idem | [conf] |
| `openWindows` | — | bajar las 4 (gate: parado) | `{applied,windows}` | [Surf] |
| `closeWindows` | — | subir las 4 (permitido siempre) | `{applied,windows}` | [Surf] |
| `setWindow` | `{area:1-4, open?:Bool}` o `{area, percent:0-100}` | una ventana; `percent` vía feature POS, luego 255 "sin petición" (gate si abre) | `{applied,windows,windowsPct}` | [?] |
| `setSunroof` | `{state:"open"/"close"/"tilt"/"stop"/"comfort"}` o `{open:Bool}` | techo (`setMoonRoofState`/feature) (gate si abre) | `{applied,windows}` | [?] (Surf sin techo) |
| `setSunshade` | `{state}`/`{open}` | cortinilla (`setSunshadeState`) | `{applied,windows}` | [?] |
| `openTrunk` | — | maletero trasero `voiceCtlBackDoor(1)` → autoservice OP_TRUNK (gate: parado) | `{applied,trunk}` | [conf] |
| `closeTrunk` | — | cerrar maletero `voiceCtlBackDoor(2)` | `{applied,trunk}` | [conf] |
| `openFrunk` | — | maletero delantero feature `SETTING_ELECTRIC_FORECABIN_SWITCH_SET` (1) (gate: parado) | `{applied}` | [?] |
| `setChargePort` | `{locked:Bool}` | bloqueo tapa de carga `setting.setChargingPort` | `{applied,chargePort}` | [Surf] |
| `setClimate` | `{on?,temp?,fan?,mode?,recirc?,auto?,maxCooling?,ventilation?,defrostFront?,defrostRear?}` | clima completo; temp 17-33, fan 0-7 (feature `AC_WIND_LEVEL_SET` si el setter con nombre es no-op) | `{applied,climate}` | [conf] AC/temp/fan/mode/ciclo/defrost |
| `setDefrost` | `{front?:Bool, rear?:Bool}` | desempañar `ac.setAcDefrostState(0,area,st)` | `{applied,climate}` | [conf] |
| `setSeatComfort` | `{driver?:-2..2, passenger?:-2..2}` | calef/vent asiento por **`setting`** (neg=vent, pos=calef, 0=off) | `{applied,seatComfort}` | [conf] |
| `setSeatHeat` | `{pos:0/1, level:0-2}` | calefacción asiento explícita | `{applied,seatComfort}` | [conf] |
| `setSeatVent` | `{pos:0/1, level:0-2}` | ventilación asiento explícita | `{applied,seatComfort}` | [conf] |
| `loadSeatMemory` | `{slot:Int}` | recuperar memoria de asiento (feature `SET_LF_MEMORY_LOCATION_SET` → `loadSeatMemory`) (gate: parado). **Mover a coordenadas NO existe en el SDK** | `{applied,slot}` | [?] |
| `setChargeLimit` | `{percent:15-70}` | límite de carga `setting.setSOCTarget` + corte por capacidad | `{applied,chargeLimit}` | [conf] |
| `batteryPreheat` | `{on:Bool}` | precalentar batería feature `CHARGING_PREHEAT_SWITCH_SET` (1/2) | `{applied}` | [?] |
| `setDriveMode` | `{mode:Int 1-21}` o `{mode:"eco"/"sport"/...}` | modo conducción feature `SETTING_TARGET_DRIVING_MODE_SET` (gate: <15 km/h) | `{applied,mode}` | [?] |
| `setOverspeedLock` | `{on:Bool}` | autobloqueo por exceso `setting.setOverspeedLock` (modo valet seguro) | `{applied,overspeedLock}` | [Surf] |
| `setSpeedLimit` | `{kmh:Int}` | aviso por velocidad `setting.setSpeedReminderValue` | `{applied,speedLimit}` | [Surf] |
| `flashLights` | — | destello de faros (localizar sin claxon) | `{applied}` | [?] |
| `locateCar` | `{whistle?:Bool=true}` | localizar: doble intermitente + claxon (feature `Security.SECURITY_HIGH_RISK_DOUBLE_FLASH/WHISTLE_WARNING_SET`; fallback hazard por `light`) | `{applied,whistle}` | [?] |
| `setInteriorLight` | `{on:Bool}` | luz interior feature `SET_INSIDE_LIGHT_STATE_SET` (2 on/1 off) | `{applied}` | [?] |
| `setMirrorHeat` | `{on:Bool}` | calefacción de espejos = desempañador trasero | `{applied}` | [?] |
| `setSentry` | `{on:Bool}` | centinela NATIVO del coche: `Settings.Global sentrymode_enabled_switch` + feature `SPECIAL_SENTRY_MODE_SET` | `{applied}` | [?] |
| `powerOff` | `{confirm:"APAGAR"}` | **antirrobo**: apaga el coche vía autoservice OP_POWER_OFF. SOLO si velocidad==0 conocida **y** marcha P **y** `confirm=="APAGAR"`; si no, error explicativo | `{applied,powerState}` | [?] ⚠ |

Acciones de cámara/centinela/config (`setSentryConfig`, `startLiveCam`, …) las sigue manejando
`MainService` directamente; `CarCommands.handle` devuelve `null` para ellas (no son suyas).

### Notas de calibración pendientes (sondeo en el coche)
- **Marcha**: se usa el mapeo de la comunidad (3=P,1=R,0=N,2=D). Registrar `gearRaw` conduciendo
  en R/N/D para confirmar; en el Surf P da 3.
- **Cierre**: confirmar `ota.getLFDoorLockState()`/OTA_LF_DOOR_LOCK y el feature de lock
  `BODYWORK_FOUR_DOOR_SET` en el Surf (los números son de la familia Seagull).
- **Feature IDs [?]**: los números son fallback validados por la comunidad en Leopard3/Seagull;
  `BydReflect.fid()` los resuelve por SÍMBOLO del catálogo del propio coche si existe. El sondeo
  (`cars/{carId}/probe`) sirve para fijarlos.
