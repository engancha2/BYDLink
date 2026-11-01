# Historial de viajes y cargas con gastos

El coche guarda los **hechos** (cuándo, cuánto, dónde, a qué hora entró cada kWh). El móvil guarda los **precios** y
calcula el coste. Así un cambio de tarifa no obliga a tocar el coche y el coste por horas sale exacto.

| Quién | Dónde | Qué |
|---|---|---|
| Coche (`data/Recorder.kt`) | `cars/{id}/trips/{tripId}` | un viaje |
| Coche | `cars/{id}/tripPoints/{tripId}/{i}` | puntos GPS (como siempre, para los móviles viejos) |
| Coche | `cars/{id}/tripRoutes/{tripId}` | ruta simplificada (≤ 300 puntos) como polilínea codificada |
| Coche | `cars/{id}/chargingSessions/{id}` | una carga |
| Móvil (`phone/history.js`) | `cars/{id}/historyCfg` | precios, lugares y gasolina |
| Móvil | `cars/{id}/historyEdits/{trips\|charges}/{id}` | correcciones a mano |

Las reglas actuales ya dejan escribir todo `cars/{id}` a quien tiene acceso; `trips` y `chargingSessions` tienen
`.indexOn: start` (las consultas por periodo usan `orderByChild('start').startAt(desde).endAt(hasta)`).

## Viaje (`trips/{tripId}`)

Campos de siempre: `start`, `end` (null mientras va), `distanceKm`, `durationSec`, `startAddr`, `endAddr` (null).
Nuevos (versión `v: 2`, coche ≥ 2.27). Cualquiera puede faltar o ser null:

| Campo | Qué es |
|---|---|
| `live` | `true` mientras el viaje sigue (se reescribe cada minuto); desaparece al cerrarse |
| `driveSec` | segundos en movimiento (velocidad > 0) |
| `avgKmh`, `maxKmh` | velocidad media en movimiento y máxima |
| `socStart`, `socEnd` | % de batería al salir y al llegar |
| `kwh`, `kwhSrc` | energía gastada y de dónde sale: `contador` (energía total del coche, si el viaje gasta ≥ 2 kWh), `bateria` (energía restante de la batería al salir − al llegar) o `soc` (% gastado × capacidad deducida) |
| `kwh100` | consumo del viaje (solo si ≥ 1 km) |
| `outTemp` | temperatura exterior media en marcha (°C) |
| `odoStart`, `odoEnd` | cuentakilómetros (si el GPS se queda corto, la distancia sale de aquí) |
| `startLat`, `startLng`, `endLat`, `endLng` | extremos (5 decimales) para nombrar el sitio |

Reglas del grabador:
- Empieza con velocidad ≥ 3 km/h (no se usa la marcha: su número no está calibrado en el Surf).
- Termina al estar parado 3 min en P / apagado / freno de mano, o 10 min parado sin más. La hora de fin es cuando se
  paró, no 3 minutos después.
- Lo que va en curso se guarda también en el coche (preferencias `recorder`): BYD mata la app al quitar el contacto y
  el vigilante la vuelve a abrir; el viaje sigue donde estaba. Si al volver lleva > 15 min sin noticias, se cierra con
  los últimos datos. Con el coche dormido (el SDK no contesta) el servicio llama `onSilence` y se cierra igual.
- Viajes de < 0,2 km o < 60 s se borran.

## Ruta (`tripRoutes/{tripId}`)

Cadena con el formato abierto "encoded polyline" (precisión 1e-5), tras simplificar con Douglas-Peucker (tolerancia
desde 10 m hasta que quedan ≤ 300 puntos). Si no existe (viajes viejos), el móvil usa `tripPoints`.

## Carga (`chargingSessions/{id}`)

Campos de siempre: `start`, `end` (null mientras carga), `socStart`, `socEnd`, `kwh`, `durationMin`, `type` (AC/DC),
`live`, `place` (null), `costEur` (null: lo calcula el móvil; si es un número —datos viejos o demo— se respeta).
Nuevos (`v: 2`):

| Campo | Qué es |
|---|---|
| `chargeMin` | minutos cargando de verdad (sin las pausas) |
| `maxKw`, `avgKw` | potencia máxima y media (kWh / horas cargando) |
| `lat`, `lng` | dónde se cargó (primera posición conocida) |
| `kwhH` | `{ "aaaammddhh": kWh }` en hora LOCAL del coche: lo que entró en cada hora. Del contador de la sesión del coche; si no lo hay, kW × tiempo; si el reparto no llega al 80 % del total, se reparte por el tiempo cargando |

Una sesión va de enchufar a desenchufar; pausas de hasta 30 min no la cortan; también se recupera tras reiniciarse la app.

## Precios (`historyCfg`)

```json
{
  "home":  { "mode": "periods", "p1": 0.25, "p2": 0.17, "p3": 0.11, "flat": 0.16 },
  "awayAc": 0.35, "dc": 0.49,
  "places": [ { "name": "Casa", "lat": 41.387, "lng": 2.17, "r": 150, "price": "home" },
              { "name": "Trabajo", "lat": 41.40, "lng": 2.17, "r": 200, "price": 0 } ],
  "unknownAc": "home",
  "gas": { "on": true, "eurL": 1.55, "l100": 6 }
}
```

- €/kWh con impuestos. Valores por defecto orientativos (los de arriba): el usuario pone los de su factura.
- `home.mode`: `periods` = 2.0TD (p1 punta, p2 llano, p3 valle) o `flat` (precio único).
- 2.0TD: valle de 0 a 8 h y todo el día sábados, domingos y festivos nacionales de fecha fija (1/1, 6/1, 1/5, 15/8,
  12/10, 1/11, 6/12, 8/12, 25/12); punta de 10 a 14 h y de 18 a 22 h; el resto, llano.
- `places`: geocercas (radio `r` en metros). `price: "home"` = tarifa de casa; un número = €/kWh fijo (0 = gratis).
- `unknownAc`: cómo contar las cargas AC sin ubicación (las viejas): `home` o `away`.

## Coste

De una carga (`phone/history.js` `chargeCost` = `car-app data/Tariff.kt` `chargeCost`; si se cambia una, cambiar la otra):
1. `historyEdits.charges[id].costEur` si existe (lo que pagaste, a mano).
2. `costEur` de la carga si es un número y no hay tipo corregido a mano.
3. Tipo: el corregido a mano (`kind`: home/away/dc/free), si no DC → `dc`; dentro de un lugar → `home` o `place`;
   fuera de lugares → `away` (o `unknownAc` si no hay ubicación o no se ha puesto Casa).
4. `home`: Σ kWh de cada hora (`kwhH`, o repartidos entre inicio y fin) × precio de esa hora. `place`: kWh × precio
   del lugar. `away`: kWh × `awayAc`. `dc`: kWh × `dc`. `free`: 0.

De un viaje: kWh del viaje (o km × consumo medio del periodo si no hay dato: se marca "≈") × €/kWh medio de tus
cargas (las del periodo si suman ≥ 5 kWh; si no, las de los 90 días anteriores; si no, el precio de valle o único).
Gasolina: km × l/100 km / 100 × €/l; ahorro = gasolina − eléctrico.

## Correcciones (`historyEdits`)

- `trips/{id}`: `{ skip: true }` = no contar el viaje.
- `charges/{id}`: `{ kind?: "home"|"away"|"dc"|"free", costEur?: número, skip?: true }`.

## Dónde se ve

- Móvil: sección **Historial** (`#history`): semana / mes / año, resumen (km, € en cargas, consumo, €/100 km, kWh,
  ahorro), gráfica por día o mes (km, kWh por tipo de carga, €), listas de viajes y cargas con detalle (mapa de la
  ruta, consumo, velocidades, batería, coste por horas), precios y lugares, y exportar CSV (`;` y coma decimal, para
  Excel en español). Entradas: tarjeta "Este mes" en Inicio (los primeros días enseña el mes pasado), botón en Mapa
  y en Carga. La lista de Carga y los títulos de Mapa usan los mismos precios y nombres (`window.BYDHistory`).
- Coche: página Energía del lanzador (`ui/HistoryCard.kt`): este mes (km, kWh gastados y cargados, €, €/100 km) y
  los últimos 5 viajes y cargas.
