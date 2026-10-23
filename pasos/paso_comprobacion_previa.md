# Comprobacion previa del backend wallet

## Objetivo

Comprobar el proyecto despues de moverlo a:

```text
C:\Proyecto\peru-wallet-mvp\peru-wallet-mvp-back-grupo2
```

Esta comprobacion abarca requisitos, compilacion, pruebas automatizadas,
empaquetado, arranque del JAR, salud, Swagger y recorrido HTTP de onboarding mock.
No equivale a una certificacion de seguridad ni de funcionamiento financiero real.

Los resultados observados se guardaran por separado en
`resultado/resultado_previa.md`, en la raiz del proyecto.

En la ejecucion documentada se utilizo **8082**, porque 8080 estaba ocupado.
El servidor de comprobacion queda activo en <http://localhost:8082/q/swagger-ui/>.
No volver a ejecutar el arranque si esa instancia sigue activa; usarla para los
pasos HTTP o detener solamente esa instancia antes de repetir el arranque.

## 1. Revisar carpeta e instrucciones

Abrir PowerShell en la carpeta del proyecto:

```powershell
Set-Location 'C:\Proyecto\peru-wallet-mvp\peru-wallet-mvp-back-grupo2'
git status --short --branch
```

Confirmar que existen `pom.xml`, `mvnw.cmd`, `src/`, `scripts/` y la configuracion
en `src/main/resources/application.properties`. Conservar los cambios del equipo.
Leer las instrucciones `AGENTS.md` que existan en la carpeta o sus padres.

## 2. Verificar herramientas, sin instalar

```powershell
.\instalar_requisitos.ps1
java --version
javac --version
.\mvnw.cmd --version
```

Esperado: JDK/Javac 21 y Maven 3.9+ ejecutandose con Java 21. Si la terminal
apunta a otro JDK, seleccionar el JDK 21 instalado para esa sesion, sin modificar
variables persistentes ni reinstalarlo. No usar `-Instalar` para esta verificacion.

El wrapper descarga su Maven/dependencias si faltan en cache; esto no reinstala
Java, Git ni un Maven global. Puede requerir internet en el primer arranque.

## 3. Compilar, ejecutar pruebas y empaquetar

```powershell
.\mvnw.cmd package -B -ntp
```

Esperado: `BUILD SUCCESS`, todas las pruebas aprobadas y el archivo ejecutable
`target/quarkus-app/quarkus-run.jar`. Revisar los resultados reales de Surefire
en `target/surefire-reports/`, no solo la existencia del JAR.

La suite comprueba onboarding, validaciones, acceso por wallet, idempotencia,
KYC mock, persistencia, fallos de escritura/logica y formulas analiticas.
Las pruebas usan su almacenamiento de test y puertos HTTP aleatorios.

No ejecutar `clean`, borrar `data/` ni resetear JSON para conseguir que pase.
Si falla algo, guardar el error y corregir solo su causa antes de continuar.

## 4. Comprobar la sintaxis de la demo PowerShell

```powershell
$tokens = $null
$errores = $null
$null = [System.Management.Automation.Language.Parser]::ParseFile(
    (Join-Path $PWD 'scripts/demo-onboarding.ps1'),
    [ref]$tokens,
    [ref]$errores
)
if ($errores.Count -gt 0) { throw 'La demo contiene errores de sintaxis.' }
```

## 5. Elegir puerto y almacenamiento aislado

Revisar 8080 y, si esta ocupado, elegir otro como 8082. No detener el servicio
de otra carpeta ni confundir el MCP de 8081 con el backend.

```powershell
Get-NetTCPConnection -LocalPort 8080,8082 -State Listen -ErrorAction SilentlyContinue
```

Para el servidor de verificacion, utilizar un archivo separado de `data/`:

```powershell
$env:WALLET_STORAGE_PATH = Join-Path $PWD 'target/verificacion-previa/wallet-store.json'
$env:WALLET_MOCK_KYC_ENABLED = 'true'
$env:WALLET_MOCK_KYC_RESULT = 'APPROVED'
Remove-Item Env:WALLET_ANALYTICS_KEY -ErrorAction SilentlyContinue
```

La eliminacion anterior afecta solo a la variable de esta terminal, no a archivos
ni al entorno persistente. Habilita el escenario por defecto: endpoints
administrativos de analitica deshabilitados. No mostrar secretos.

Si ese archivo de verificacion ya existe, conservarlo: la demo agrega una wallet
ficticia nueva con un token y canal propios. No borra las anteriores.

## 6. Arrancar el backend empaquetado

En una terminal independiente, usando el entorno del paso anterior:

```powershell
java '-Dquarkus.http.port=8082' -jar target/quarkus-app/quarkus-run.jar
```

Sustituir 8082 si se eligio otro puerto. Esperado: arranque Quarkus sin errores,
direccion `127.0.0.1:<puerto>` y proceso activo. El perfil del JAR es `prod`,
pero sus operaciones siguen siendo mock; no significa despliegue productivo.

## 7. Verificar HTTP y documentacion

En otra terminal:

```powershell
$baseUrl = 'http://localhost:8082'
Invoke-RestMethod "$baseUrl/q/health"
(Invoke-WebRequest "$baseUrl/q/swagger-ui/" -UseBasicParsing).StatusCode
(Invoke-WebRequest "$baseUrl/q/openapi" -UseBasicParsing).StatusCode
```

Esperado: salud `UP`, Swagger HTTP 200 y contrato OpenAPI HTTP 200.
La raiz `/` no es una pantalla del producto; este proyecto es un backend REST.

## 8. Ejecutar el flujo real de onboarding mock

Desde la raiz del proyecto, con el servidor activo:

```powershell
.\scripts\demo-onboarding.ps1 -BaseUrl $baseUrl
```

El script debe:

1. Generar un token aleatorio sin imprimirlo e iniciar una wallet PE.
2. Reintentar el registro y comprobar que conserva el walletId.
3. Enviar perfil invalido, recibir HTTP 400 y confirmar que no avanza.
4. Guardar perfil ficticio valido y avanzar a verificacion.
5. Ejecutar KYC simulado APPROVED y llegar a consentimiento.
6. Aceptar consentimiento, obtener ACTIVE y acceso a pagos MOCK.
7. Registrar actividad y comprobar que repetir su clave no duplica el evento.

La demo crea datos ficticios, no procesa dinero. Si analitica esta deshabilitada,
su parte administrativa se omite y debe declararse asi en el resultado. Las
pruebas automatizadas si deben verificar endpoints y formulas con clave de test.

## 9. Comprobar seguridad por defecto y persistencia

- Sin clave administrativa configurada, `/api/analytics/monthly?month=2026-10`
  debe devolver 503, no datos ni un 500 por mala configuracion.
- Confirmar que el JSON aislado existe y puede analizarse con `ConvertFrom-Json`.
- Confirmar estado ACTIVE de la wallet de la demo y eventos de inicio, tres
  pasos, cierre y actividad; no imprimir tokens ni informacion sensible.
- Confirmar que los tokens de wallet se almacenan como hash SHA-256, no en claro.
- La suite verifica reinicio/lectura, archivo corrupto y abortos sin cambios parciales.
  No simular corrupcion manualmente sobre un archivo de trabajo.

## 10. Registrar resultados y limitaciones

En `resultado/resultado_previa.md` conservar fecha, ruta, rama/commit si existe,
versiones reales, comandos, conteo de pruebas, puerto, resultados HTTP y demo,
ubicacion del JSON aislado y cualquier fallo o paso omitido.

No afirmar cumplimiento KYC, pagos reales, mejoras comerciales de onboarding,
metas MAU/CAC ni seguridad completa por aprobar esta comprobacion tecnica.
No hacer commit/push de los documentos sin indicacion expresa.