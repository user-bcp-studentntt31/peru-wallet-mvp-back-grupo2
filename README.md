# Wallet Peru: registro y activacion mock

Backend REST en **Java 21 + Quarkus 3.27.0**, Maven Wrapper y persistencia JSON.
Primer alcance: onboarding guiado, validaciones, activacion mock e instrumentacion.
No contiene un frontend, movimientos de dinero ni integraciones financieras reales.

## Arranque rapido

Desde PowerShell, con JDK 21 y JAVA_HOME configurado:

```powershell
cd C:\Proyecto\reqflow-workspace\hackaton
.\mvnw.cmd test
.\mvnw.cmd quarkus:dev
```

En Git Bash: `./mvnw test` y `./mvnw quarkus:dev`.
La primera ejecucion del wrapper necesita internet para descargar Maven/dependencias.
Si falta Java, consultar `requerimientos_instalacion.md` y ejecutar primero el
diagnostico `instalar_requisitos.ps1`, sin instalar otra vez lo ya disponible.

- Swagger UI: <http://localhost:8080/q/swagger-ui/>.
- Contrato OpenAPI: <http://localhost:8080/q/openapi>.
- Health: <http://localhost:8080/q/health>.
- API: <http://localhost:8080/api/wallets>.

No hay una pagina web en `/`: usa Swagger o los ejemplos de API. Si 8080 esta
ocupado, arrancar con `"-Dquarkus.http.port=8082"` y adaptar la URL de la demo.
El servidor se limita a loopback. No exponerlo a internet con estas credenciales demo.

## Estructura MVC sencilla

```text
hackaton/
  pom.xml                         dependencias y version de Quarkus
  mvnw / mvnw.cmd                  comandos Maven portables
  .mvn/wrapper/                   version de Maven fijada
  src/main/java/pe/wallet/
    controller/                   C: endpoints, errores y headers HTTP
    service/                      casos de uso, estados y analitica
    model/WalletData.java          M: entidades y enums del dominio
    repository/                   lectura/escritura JSON
    view/ApiModels.java            V: peticiones y respuestas JSON (DTO)
  src/main/resources/
    application.properties        configuracion del backend
  src/test/java/pe/wallet/         pruebas HTTP, servicios y persistencia
  scripts/demo-onboarding.ps1      recorrido real de API con datos ficticios
  docs/criterios_aceptacion.md     criterios, supuestos y limitaciones
  data/wallet-store.json          datos de desarrollo, creado al primer cambio
  target/test-data/               datos aislados de pruebas
```

En una API, la vista es la representacion JSON, no una pantalla HTML. La separacion
es `Controller -> Service -> Repository -> JSON`. Los DTO se agrupan en una clase
y el modelo en otra para mantener pocas piezas y facilitar la lectura.

## Progreso y estados

Propuesta de pasos para esta demo, pendiente de validacion de Producto/Cumplimiento:

```text
PROFILE -> VERIFICATION -> CONSENT -> ACTIVE
```

Cada respuesta de onboarding incluye `steps`, posiciones, completados,
`currentStep`, `nextAction`, fechas, `paymentsAvailable` y `mock: true`.
La consulta GET permite reanudar el mismo flujo despues de un reinicio.

- Perfil: nombre ficticio y correo valido terminado en `.test`.
- Verificacion: el servidor consulta su resultado KYC simulado, no un booleano
  de aprobacion que el cliente pueda enviar.
- Consentimiento: solo `accepted: true` y despues de aprobar la verificacion.
- `PENDING_VERIFICATION` no permite avanzar a consentimiento ni a pagos.
- `ACTIVE` habilita solamente el punto de entrada **mock**, no pagos reales.

Desactivar KYC mock sin implementar un proveedor real bloquea verificacion con
503; no omite el paso ni activa por defecto. No se afirma cumplimiento KYC legal.

## Endpoints

Todos los endpoints de wallet usan `Authorization: Bearer <token-demo>`.
El cliente genera 32 bytes aleatorios y los codifica en Base64URL (43 caracteres)
antes de iniciar; conserva ese mismo token y el walletId. Solo su hash SHA-256
queda en el JSON. No compartir el token ni incluirlo en URL o capturas.

| Metodo y ruta | Funcion |
| --- | --- |
| `POST /api/wallets` | Iniciar con `{ "country": "PE", "channel": "organic" }` e `Idempotency-Key`. 201 nuevo, 200 reintento. |
| `GET /api/wallets/{id}/onboarding` | Consultar y reanudar progreso. |
| `PUT /api/wallets/{id}/onboarding/profile` | Guardar `{ "displayName": "Usuario Demo", "email": "demo@wallet.test" }`. |
| `POST /api/wallets/{id}/onboarding/verification` | Pedir verificacion mock, sin cuerpo. Solo en modo `DEMO`. |
| `PUT /api/wallets/{id}/onboarding/consent` | Aceptar `{ "accepted": true }` y activar atomicamente. |
| `GET /api/wallets/{id}/payments/entry` | Entrada de pagos, solo ACTIVE y solo del propietario. No transaccional (`transactional: false`). |
| `POST /api/wallets/{id}/activity` | Registrar apertura del acceso a pagos mock; requiere nueva `Idempotency-Key` por actividad. |

La clave de registro no se reutiliza con otra wallet o datos. El mismo token no
puede crear otra wallet cambiando la clave de registro. Un perfil confirmado no
se edita en esta HU; el mismo PUT es idempotente, otros valores devuelven 409.
Repetir verificacion o consentimiento completados no duplica eventos.

Una wallet inexistente y una wallet de otra persona responden igual
(`404 WALLET_NOT_FOUND`) para no revelar si el recurso existe. Sin token valido: 401.
Los errores nunca repiten los valores enviados; solo indican los campos a corregir.

### Estados y verificacion

`status`: `ONBOARDING`, `PENDING_VERIFICATION`, `VERIFICATION_REJECTED` o `ACTIVE`.
`verification` informa `status` (`NOT_STARTED`, `PENDING`, `REJECTED`, `APPROVED`) y
`simulated`. Con `simulated: true` el resultado es de demostracion, no KYC real.
Un rechazo se guarda y se ve en `GET onboarding`; `POST verification` responde 422
y se puede reintentar. Pendiente, rechazado o desconocido nunca activan la wallet.

### Modo demo y produccion

`WALLET_MODE` (predeterminado `DEMO`). Solo `DEMO` acepta la verificacion simulada.
Cualquier otro valor se trata como produccion y `POST verification` responde
`503 KYC_NOT_CONFIGURED`: sin un proveedor real aprobado no se activa ninguna wallet,
aunque `WALLET_MOCK_KYC_RESULT` sea `APPROVED`. Wallets, eventos y gastos guardan el
modo con el que se crearon, y los reportes solo agregan datos del modo vigente.

Este token de posesion es **autorizacion demo**, no login productivo: no implementa
OIDC, MFA, expiracion, recuperacion de token perdido ni recuperacion de wallet por
identidad. Conservarlo fuera de logs y del repositorio. Nunca usar datos personales reales.

## Analitica administrativa

Deshabilitada por defecto. Para habilitarla, define una clave privada local antes
de arrancar el servidor:

```powershell
$bytes = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($bytes)
$rng.Dispose()
$env:WALLET_ANALYTICS_KEY = [Convert]::ToBase64String($bytes)
.\mvnw.cmd quarkus:dev
```

No imprimir la clave. Para ejecutar la parte administrativa de la demo en otra
terminal, configura privadamente la misma clave en esa terminal; cambiar una
variable del cliente no cambia la del servidor ya iniciado. Tambien puedes copiar
`.env.example` a `.env` solo si no existe y completar la clave privadamente.

| Metodo y ruta | Cabeceras y parametros |
| --- | --- |
| `POST /api/analytics/acquisition-costs` | `X-Analytics-Key`, `Idempotency-Key`; `{ "month":"2026-10", "channel":"organic", "amountCents":1000 }`. Entero en centimos de PEN; los decimales se rechazan con 400. |
| `GET /api/analytics/monthly?month=2026-10` | `X-Analytics-Key`; filtro `channel` opcional. |
| `GET /api/analytics/events?walletId={id}&offset=0&limit=100` | `X-Analytics-Key`; walletId opcional, limite maximo 200. |

Eventos: `ONBOARDING_STARTED`, `STEP_COMPLETED` (con el paso, sin valores),
`ONBOARDING_COMPLETED` y `WALLET_ACTIVITY`. Cada evento solo trae `id`, `walletId`,
`type`, `step`, `occurredAt` (UTC, generado por el servidor) y `mode`. Los reportes
mensuales usan `America/Lima`. Los eventos no contienen nombre, email ni tokens.
No se emite `onboarding_exited`: una salida explicita no es observable de forma
fiable y un cierre, desconexion o expiracion no se interpreta como salida (D5).

**Definiciones propuestas, no aprobadas aun por PO:**

- Mediana: duracion entre el primer inicio persistido y la activacion correcta,
  incluyendo pausas/reanudacion (el inicio no se reinicia al retomar), para las
  wallets activadas en ese mes y canal. Target de referencia: 180 segundos, expuesto
  con `targetEvaluation: NOT_EVALUATED` hasta que Producto defina cohorte y periodo (D6).
- `activatedWallets`: wallets activadas en el mes. No equivale a MAU.
- `monthlyActiveUsers` y `acquisitionCost` son `{ available, value, unit, reason }`.
  Se devuelven con `available: false` y `value: null` mientras falten la definicion y
  fuente de MAU (D7) o la fuente de gasto, atribucion y denominador de CAC (D8).
  Nunca se sustituyen por activaciones ni por eventos de actividad mock. Cuando
  Producto los apruebe se habilitan con `WALLET_METRICS_MAU_APPROVED=true` y
  `WALLET_METRICS_CAC_APPROVED=true`; el CAC es entero en centimos, redondeado a
  centimo con la mitad hacia arriba.
- Sin gasto registrado, `acquisitionSpendCents` es `null`; no se inventa gasto cero.
  Si hay cero activaciones, el CAC no esta disponible incluso con gasto informado.
- La vista `ALL` agrega los gastos disponibles, no certifica integridad de las
  fuentes de gasto. Verificar canales faltantes antes de interpretar el total.
- Lanzamiento, ventanas meses 1-3, identidad adquirida vs activada y actividad oficial
  quedan pendientes. La API mensual permite medir, no asegura alcanzar MAU 1000-2000,
  CAC PEN 10 ni la mediana de 3 minutos en produccion.

## Persistencia JSON

El archivo se crea automaticamente al primer cambio y guarda wallets, eventos,
gastos e idempotencia en una misma escritura. No se resetea al arrancar.
Esquema v2: los gastos usan `amountCents`; un archivo v1 (`amountPen`) se migra al leerlo.

- Bloqueo de archivo y exclusividad dentro de la instancia para evitar cambios perdidos.
- Escritura a temporal, `force` y reemplazo atomico: no hay fallback que trunque el
  archivo existente si el sistema de archivos no permite reemplazo atomico.
- Un error de negocio aborta la escritura completa: wallet y eventos no se separan.
- Archivo corrupto, esquema incompleto, bloqueo o permiso denegado: 503, no borrado.
- Conservar copia segura antes de recuperar un JSON dañado. No editarlo mientras
  corre el servidor ni sincronizarlo con otra instancia. No hay backup automatico.
- Solo una instancia local, sin garantias de almacenamiento distribuido, ledger
  financiero, alta disponibilidad ni acceso sobre carpetas de red.
- `data/` se excluye de Git; las pruebas usan `target/` y archivos temporales propios.

| Variable | Valor predeterminado |
| --- | --- |
| `WALLET_STORAGE_PATH` | `data/wallet-store.json`, relativo a la carpeta de arranque. |
| `WALLET_MOCK_KYC_ENABLED` | `true` |
| `WALLET_MOCK_KYC_RESULT` | `APPROVED`; admite `PENDING` o `REJECTED`. |
| `WALLET_MODE` | `DEMO`. Otro valor = produccion: la verificacion simulada nunca activa. |
| `WALLET_METRICS_MAU_APPROVED` / `WALLET_METRICS_CAC_APPROVED` | `false`: MAU y CAC no disponibles. |
| `WALLET_ANALYTICS_KEY` | Vacio: analitica devuelve 503. |

## Probar con un recorrido real

Con el servidor activo, desde otra PowerShell:

```powershell
.\scripts\demo-onboarding.ps1
```

El script crea un token aleatorio sin mostrarlo, inicia una wallet mock, prueba
un perfil invalido (400), completa los tres pasos y confirma entrada a pagos.
Tambien reintenta registro y actividad para verificar que no haya duplicados.
Si esa terminal tiene `WALLET_ANALYTICS_KEY`, registra gasto ficticio PEN 10 y
comprueba un reporte de la cohorte unica creada por la demo. Si no, omite analitica.
El recorrido crea datos ficticios persistentes; no es una operacion de solo lectura.

Para un puerto distinto: `-BaseUrl http://localhost:8082`.

## Pruebas y empaquetado

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
java -jar target/quarkus-app/quarkus-run.jar
```

Parar el proceso anterior antes de usar el mismo puerto. Mantener toda la carpeta
`target/quarkus-app`, no solo el JAR. Las pruebas HTTP usan puerto aleatorio para
no interferir con el MCP del laboratorio en 8081.

La suite comprueba persistencia/reinicio, corrupcion, abortos, concurrencia,
validaciones, secuencia, permisos, reintentos, KYC pendiente/rechazado/deshabilitado,
eventos y formulas con fixtures conocidos, incluido el limite mensual de Lima.
No ejecuta KYC real, pagos, mediciones comerciales ni pruebas de seguridad completas.

## Errores habituales

400: campos invalidos/clave de idempotencia ausente; 401: credencial ausente o ajena;
404: wallet o ruta inexistente; 409: orden, conflicto o wallet aun no activa;
422: rechazo KYC mock; 503: almacen o dependencia no disponible. Los errores de
negocio/validacion incluyen `code`, `message`, `fields` y `timestamp`, sin trazas.
Las respuestas API no deben cachearse.

Siguiente trabajo: validar [criterios y supuestos](docs/criterios_aceptacion.md)
con Arquitectura, Frontend, Producto y Cumplimiento antes de convertir esta demo
en una implementacion de onboarding financiero real.
# peru-wallet-mvp-back-grupo2
