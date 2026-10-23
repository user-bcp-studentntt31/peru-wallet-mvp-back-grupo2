# HU-01: registro y activacion del MVP de wallet

## Fuente y alcance

Historia recibida: como usuario de smartphone que hace transferencias de bajo
monto, completar onboarding guiado para empezar a pagar sin abandonar el proceso.
Epica: MVP de wallet en Peru para pagos cotidianos.

Este backend es una **demostracion local** con datos ficticios, sin frontend ni
integraciones bancarias. Los pasos concretos y reglas analiticas siguientes son
propuestas tecnicas para poder probar la HU, no decisiones legales ni comerciales.

## Trazabilidad

| Criterio recibido | Implementacion y comprobacion | Limite |
| --- | --- | --- |
| 1. Secuencia y paso actual visibles | La API devuelve tres pasos, posicion, completados, actual y siguiente accion. OnboardingApiTest verifica el recorrido. | La pantalla debe implementarla Frontend. |
| 2. Bloquear datos obligatorios ausentes o invalidos con mensaje accionable | Bean Validation, errores con campos y estado sin avance. Validacion de orden en servicio. | Campos de perfil/KYC definitivos pendientes. |
| 3. Al finalizar, estado activo y acceso al punto de entrada de pagos | Ultima operacion valida consentimiento y guarda ACTIVE junto a eventos; GET payments/entry exige ACTIVE. | Activacion y pagos exclusivamente mock. |
| 4. Eventos de inicio, cada paso y cierre | Eventos con ID y timestamp de servidor; reintentos no duplican completados. Almacen atomico. | No hay broker ni integracion externa de analitica. Cierre significa exito; abandono se infiere de ausencia de cierre, no de un evento de navegador garantizado. |
| 5. Mediana de 8 a 3 minutos dentro del trimestre | Calcula mediana mensual de casos completados; tests verifican calculo y fixtures. Devuelve target 180 segundos. | No demuestra mejora real ni define la fecha de lanzamiento. La frontera inicio-fin requiere aprobacion. |
| 6. Conteos y gasto para verificar MAU/CAC meses 1-3 | Activaciones por mes/canal y gasto en centimos. MAU y CAC se devuelven como no disponibles (`available: false`) hasta que Producto apruebe definiciones y fuentes (D7, D8). | Nuevos activos != MAU. Origen confiable del gasto, usuario adquirido, actividad y ventanas comerciales requieren aprobacion. |

Matriz completa criterio -> tarea -> prueba y bloqueos abiertos: `docs/matriz_trazabilidad.md`.

## Supuestos de demo

1. Cohorte `PE`, sin reglas KYC documentales reales. No se capturan DNI ni documentos.
2. Pasos: perfil ficticio, resultado de proveedor KYC simulado, consentimiento demo.
3. Correo terminado en `.test`, nombre ficticio, canal de adquisicion identificable.
4. Token opaco generado por cliente para posesion de su wallet. No equivale a un
   mecanismo de autenticacion financiero. Sin recuperacion/rotacion/expiracion.
5. Mediana incluye pausas desde creacion de onboarding hasta activacion correcta.
6. Calendario mensual de Lima. Los targets se exponen como referencias, no como
   indicadores de logro ni promesas de crecimiento.
7. MAU demo depende de eventos explicitos de apertura del acceso a pagos, separado
   de activacion. Las futuras operaciones reales deberan producir actividad confiable.
8. CAC usa activaciones como proxy de adquisicion; PO debe decidir si es el
   denominador correcto. No equivale a conocer clientes adquiridos reales.
9. Persistencia en un JSON local, una sola instancia; sin procesamiento monetario.

## Decisiones pendientes antes de produccion

- Producto/Cumplimiento: pasos, campos, KYC, terminos y autorizacion de activacion.
- Arquitectura/Seguridad: login real, identidad unica, recuperacion, MFA y secretos.
- Producto/Datos: inicio y fin oficiales, pausas, abandonos, cohortes y zona horaria.
- Producto/Marketing: fuentes y completitud del gasto, atribucion, adquisicion y meses 1-3.
- Operaciones: respaldo, recuperacion y persistencia transaccional apropiada para dinero.
- Frontend: flujo visible y accesible, estados pendientes, errores y uso del contrato API.

No se declara cumplimiento regulatorio, disponibilidad 24x7, reduccion de abandono,
mejora de tiempo real ni logro de MAU/CAC por pasar pruebas con datos sintéticos.

## Verificacion ejecutada el 23 de octubre de 2026

- `mvnw.cmd package -B -ntp`: BUILD SUCCESS, 20 pruebas, cero fallos/errores.
- Pruebas HTTP: 6; repositorio JSON: 5; control de acceso: 2; analitica: 3;
   servicio onboarding/KYC: 4.
- JAR empaquetado arrancado en `http://localhost:8080` con Java 21.
- Health `UP` y Swagger HTTP 200.
- Demo PowerShell contra el servidor: registro y reintento, perfil invalido 400
   sin avance, perfil valido, verificacion simulada, consentimiento, ACTIVE,
   entrada de pagos MOCK y reintento de actividad sin duplicados.
- Sin clave administrativa: analitica 503, backend disponible. La parte analitica
   de la demo manual se omitio; sus endpoints/formulas si se probaron en la suite.
- Pruebas usan almacenamiento aislado. La demo deja una wallet ficticia en
   `data/wallet-store.json`, sin guardar su token de acceso en claro.

No se ejecutaron pagos reales, KYC externo, pruebas de carga ni un despliegue
productivo. No se ha creado repositorio Git ni publicado codigo a GitHub.