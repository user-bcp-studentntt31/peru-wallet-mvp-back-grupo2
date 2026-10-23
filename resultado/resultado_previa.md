# Resultado de la comprobacion previa

## Veredicto

**APROBADO para continuar el desarrollo y la demostracion local del backend mock.**

El proyecto compila, aprueba sus 20 pruebas, genera el paquete ejecutable y
completa un onboarding HTTP real contra el JAR construido en su nueva ubicacion.
No se encontraron fallos en las comprobaciones ejecutadas. Esto no demuestra
ausencia de defectos ni habilita pagos o KYC reales.

## Identificacion de esta ejecucion

| Dato | Valor observado |
| --- | --- |
| Fecha | 23 de octubre de 2026 |
| Cierre de comprobacion de datos | 10:32:00, UTC-05:00 |
| Carpeta | `C:\Proyecto\peru-wallet-mvp\peru-wallet-mvp-back-grupo2` |
| Rama | `main` |
| Commit de referencia | `2891165`, mensaje `first commit` |
| Estado Git antes de ejecutar pruebas | `main...origin/main`, con la nueva carpeta `pasos/` sin seguimiento. |
| Sistema | Windows 11, amd64 |
| JDK y Javac | Eclipse Temurin 21.0.12.1 |
| Maven global detectado | 3.10.0, existente; no reinstalado. |
| Maven usado para compilar | 3.9.16 mediante `mvnw.cmd`. |
| Quarkus | 3.27.0 |
| Aplicacion | `pe.wallet:wallet-onboarding:1.0.0-SNAPSHOT` |

No se instalaron herramientas, no se cambiaron variables persistentes, no se
modifico codigo Java ni se realizo commit/push. Se selecciono JAVA_HOME 21 solo
en las terminales de ejecucion.

## 1. Requisitos y sintaxis: correctos

Comandos ejecutados desde la nueva carpeta, o usando su ruta absoluta:

```powershell
.\instalar_requisitos.ps1
java --version
javac --version
.\mvnw.cmd --version
```

El instalador se ejecuto sin `-Instalar`, solo en modo diagnostico. Detecto JDK 21,
Maven y Git. Java/Javac y el wrapper respondieron correctamente. Se analizo
`scripts/demo-onboarding.ps1` con el parser de PowerShell: sin errores de sintaxis.

## 2. Compilacion, pruebas y paquete: correctos

Comando ejecutado:

```powershell
.\mvnw.cmd package -B -ntp
```

Salida principal observada:

```text
Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 01:13 min
Finished at: 2026-10-23T10:29:54-05:00
```

Se contrastaron los conteos con los XML de Surefire, no solo con la consola:

| Suite | Pruebas | Fallos | Errores |
| --- | --- | --- | --- |
| `OnboardingApiTest` | 6 | 0 | 0 |
| `JsonWalletRepositoryTest` | 5 | 0 | 0 |
| `AccessControlTest` | 2 | 0 | 0 |
| `AnalyticsServiceTest` | 3 | 0 | 0 |
| `WalletServiceTest` | 4 | 0 | 0 |
| **Total** | **20** | **0** | **0** |

La suite cubre recorrido HTTP, validaciones, pasos fuera de orden, credenciales,
idempotencia, KYC pendiente/rechazado/deshabilitado, persistencia tras lectura
con otra instancia de repositorio, JSON corrupto/incompleto, abortos,
concurrencia dentro de una instancia y calculos de mediana/MAU/CAC con fixtures.
No afirmar cobertura porcentual: no se ejecuto un reporte de cobertura.

Paquete generado:

```text
target/quarkus-app/quarkus-run.jar
```

Evidencias locales:

- `target/verificacion-previa/maven-package.log`: salida de compilacion y pruebas.
- `target/surefire-reports/`: reportes de las cinco suites.

Estos archivos estan bajo `target/`, excluido de Git. Pueden desaparecer si se
ejecuta `clean`; este informe conserva el resumen de la ejecucion observada.

## 3. Arranque del JAR: correcto

8080 estaba ocupado. Se eligio **8082** sin detener ni modificar el servicio que
ocupaba 8080. No se reutilizo la aplicacion de la carpeta anterior para las pruebas.

Configuracion de esta instancia, aplicada solo a su terminal:

```text
WALLET_STORAGE_PATH=<raiz-nueva>/target/verificacion-previa/wallet-store.json
WALLET_MOCK_KYC_ENABLED=true
WALLET_MOCK_KYC_RESULT=APPROVED
WALLET_ANALYTICS_KEY sin configurar
```

Comando de arranque:

```powershell
java '-Dquarkus.http.port=8082' -jar target/quarkus-app/quarkus-run.jar
```

Resultado observado:

```text
wallet-onboarding 1.0.0-SNAPSHOT on JVM (powered by Quarkus 3.27.0)
started in 2.561s. Listening on: http://127.0.0.1:8082
Profile prod activated.
```

`prod` indica el perfil del paquete, no que esta demo este lista para produccion.
La instancia se deja activa en la terminal de comprobacion para poder probarla.

## 4. Salud y documentacion HTTP: correctas

| Comprobacion | Resultado real |
| --- | --- |
| `GET http://localhost:8082/q/health` | HTTP 200; estado `UP`. |
| `GET http://localhost:8082/q/swagger-ui/` | HTTP 200. |
| `GET http://localhost:8082/q/openapi`, aceptando JSON | Respuesta leida; OpenAPI 3.1.0, contiene `/api/wallets`. |
| `GET /api/analytics/monthly?month=2026-10`, sin clave configurada | HTTP 503 esperado: administracion deshabilitada. No es un fallo de arranque. |

Swagger de esta copia: <http://localhost:8082/q/swagger-ui/>.

## 5. Recorrido HTTP real: correcto

Comando ejecutado:

```powershell
.\scripts\demo-onboarding.ps1 -BaseUrl 'http://localhost:8082'
```

| Paso | Resultado observado |
| --- | --- |
| Crear wallet PE con token generado por la demo | Wallet creada y paso inicial `PROFILE`. |
| Repetir registro con la misma clave | Se conservo el walletId; no se creo otra wallet. |
| Enviar nombre vacio/correo invalido | HTTP 400; el flujo siguio en `PROFILE`. |
| Enviar perfil ficticio valido | Perfil guardado; siguiente paso `VERIFICATION`. |
| Ejecutar KYC simulado APPROVED | Paso siguiente `CONSENT`. |
| Aceptar consentimiento | Estado `ACTIVE`. |
| Consultar entrada de pagos | Disponible en modo `MOCK`. No hubo movimientos de dinero. |
| Registrar y reintentar actividad | Mismo eventId; no se duplico el evento. |

Wallet ficticia de esta ejecucion:

```text
b0fdece2-3ba1-408a-9211-eaf0f0237d94
```

Salida principal:

```text
OK: perfil invalido bloqueado con HTTP 400.
Perfil valido guardado; paso actual: VERIFICATION
OK: wallet ACTIVE, pagos MOCK y actividad idempotente.
```

Evidencia local: `target/verificacion-previa/demo-onboarding.log`.
El token de posesion fue generado en memoria y no se muestra en este informe.

## 6. Persistencia JSON: correcta para el recorrido probado

Archivo utilizado:

```text
target/verificacion-previa/wallet-store.json
```

Se analizo con `ConvertFrom-Json` y se verifico:

- Una wallet persistida en estado `ACTIVE`.
- Token almacenado como hash SHA-256 de 64 caracteres hexadecimales, no en claro.
- Seis eventos, con estos conteos:

| Tipo | Cantidad |
| --- | --- |
| `ONBOARDING_STARTED` | 1 |
| `STEP_COMPLETED` | 3 |
| `ONBOARDING_COMPLETED` | 1 |
| `WALLET_ACTIVITY` | 1 |

Los reintentos de esta demo no aumentaron indebidamente esos conteos.
No existia ni se creo `data/wallet-store.json` en la nueva carpeta. No se borraron
datos de la carpeta antigua ni se simulo corrupcion sobre archivos de trabajo.

## 7. Limites y pasos no ejecutados

- La parte administrativa de la demo manual se **OMITIO** porque el servidor no
  tiene clave de analitica configurada. Se verifico el 503 por defecto. Endpoints
  administrativos y formulas si pasaron en las pruebas automatizadas con clave de test.
- No se realizo un reinicio manual del proceso Java despues de la demo. La
  relectura tras crear otro repositorio JSON esta cubierta por la suite automatizada.
- No se ejecutaron pagos reales, validacion KYC externa, frontend, integraciones
  bancarias, pruebas de carga, analisis de dependencias/vulnerabilidades ni auditoria
  de seguridad completa.
- No se midio el tiempo mediano real del MVP ni se demostraron objetivos MAU/CAC.
- La instalacion de requisitos en una maquina limpia no fue probada en esta sesion.

## Conclusion y siguiente paso

La copia trasladada funciona para las comprobaciones tecnicas ejecutadas y se
puede usar como base de desarrollo local. Para revisar el contrato y probar
endpoints, utilizar Swagger en el puerto **8082**, no el servicio previo de 8080.

Antes de nuevos cambios: acordar Issue/criterio, preservar el JSON de trabajo y
ejecutar las pruebas de la parte modificada. No confundir esta aprobacion de demo
con autorizacion de una wallet financiera productiva.