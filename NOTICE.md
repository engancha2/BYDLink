# Créditos y avisos de terceros

BYDLink es código propio (licencia GPL-3.0, ver `LICENSE`). Algunas técnicas se basan en proyectos públicos:

- **byd-apps** (https://github.com/wheregoes/byd-apps, licencia MIT): la idea de envolver el `Context` para que el SDK de
  BYD (`android.hardware.bydauto.*`) no exija los permisos de firma, en `car-app/.../car/BydPermissionContext.kt`.
  La licencia MIT pide conservar este aviso.

- **Leaflet 1.9.4** (https://leafletjs.com, licencia BSD-2-Clause, © Vladimir Agafonkin): el mapa de la página Mapa del
  lanzador va incluido en `car-app/app/src/main/assets/leaflet/` con su `LICENSE`. Mapas © OpenStreetMap, teselas © CARTO.

Referencias estudiadas solo como fuente de ideas (no se ha copiado su código):
- BYDMate (PolyForm Noncommercial 1.0.0, © AndyShaman): modelo de sesiones e historial de carga.
- DiAuto (AGPL-3.0) y byd-split-launcher (MIT, © homeo26): ideas de lanzador y cuadro de instrumentos.
- Lanzadores comerciales (KimLauncher, Kinex, Dudu, Electro): solo se ha observado a qué partes del sistema llaman.

BYD, DiLink y los nombres de modelos son marcas de sus dueños. BYDLink no está afiliado a BYD.
