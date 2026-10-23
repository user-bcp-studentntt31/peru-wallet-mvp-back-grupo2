# Matriz de trazabilidad HU-01 a HU-03 (backend)

Fuente: criterios de las HU del tablero (`user-bcp-studentntt31/projects/2`) y tareas backend
`back-grupo2#5` a `#22`. Las pruebas citadas estan en `src/test/java/pe/wallet/`.
Los datos son ficticios; la verificacion es simulada y no equivale a KYC real.

## HU-01 Crear cuenta y completar el onboarding guiado

| Criterio | Tarea | Prueba / evidencia | Estado |
| --- | --- | --- | --- |
| Alta con el mecanismo aprobado; sin onboarding de identidad no autenticada | BE-02 | `FlowIntegrationTest.hu01...` (401 sin token), `OnboardingApiTest.rejectsMissingCredentials...` | Parcial: se usa un token de posesion demo; el mecanismo real depende de D2. |
| Cuenta existente continua su onboarding | BE-02, BE-04 | `WalletServiceTest.resumingKeepsTheOriginalStart...` | Parcial (D2). |
| Una persona no accede ni modifica el onboarding de otra | BE-02 | `OnboardingContractTest.everyWalletEndpointTreatsAForeignWalletLikeAMissingOne`, `WalletServiceTest.anotherPersonAndAMissingWalletGetTheSameAnswer` | Cubierto. 404 identico para ajeno e inexistente. |
| Pasos requeridos, paso actual y accion siguiente | BE-01 | `OnboardingApiTest`, `FlowIntegrationTest.hu01...`, OpenAPI en `/q/openapi` | Cubierto. |
| Dato faltante o invalido no avanza; indicacion accionable | BE-03 | `OnboardingContractTest.missingOrInvalidProfileDataIsRejectedWithoutAdvancing` | Cubierto para los campos demo. |
| Paso completado se refleja y muestra la siguiente accion | BE-03 | `OnboardingApiTest`, `FlowIntegrationTest.hu01...` | Cubierto. |
| Reanudacion tras interrupcion | BE-04 | `WalletServiceTest.resumingKeeps...`, `JsonWalletRepositoryTest`, `FlowIntegrationTest.hu01...` | Cubierto en JSON local; persistencia productiva depende de D10. |
| No inventar campos, documentos ni reglas | BE-03 | `OnboardingContractTest.onboardingRequestsOnlyContainTheApprovedDemoFields` | Cubierto: solo `displayName` y `email` ficticios. |
| Privacidad: valores personales fuera de logs, errores y telemetria | BE-05 | `OnboardingContractTest.personalValuesNeverAppearInLogsErrorsOrAnalytics` | Cubierto. DNI `TEST-**` y celular `^9\d{8}$` no se implementan: no estan aprobados (D3). |
| Accesibilidad de teclado y lectores | FE | No aplica al backend | Frontend. |

## HU-02 Resultado de verificacion y activacion

| Criterio | Tarea | Prueba / evidencia | Estado |
| --- | --- | --- | --- |
| Faltan pasos: wallet no activa y siguiente paso | BE-02 | `FlowIntegrationTest.hu02...` | Cubierto. |
| Verificacion pendiente o no aprobada: estado real, wallet no activa | BE-02 | `PendingVerificationApiTest`, `RejectedVerificationApiTest`, `WalletServiceTest` | Cubierto. |
| Verificacion simulada identificada como tal y no habilita wallet productiva | BE-01 (HU-02) | `ProductionModeApiTest`, `WalletServiceTest.approvedMockResultCannotActivate...`, `verification.simulated` | Cubierto. |
| Todos los pasos aprobados: wallet activa y acceso no transaccional | BE-02, BE-03 | `FlowIntegrationTest.hu02...`, `OnboardingApiTest` | Cubierto. |
| Wallet inactiva: el acceso no se presenta habilitado | BE-03 | `paymentEntry` 409 en las pruebas anteriores | Cubierto. |
| Wallet activa accede sin procesar pagos | BE-03 | `OnboardingContractTest.openApi...` (sin rutas de pago), `transactional: false` | Cubierto. |
| Activacion atomica e idempotente, sin eventos duplicados | BE-02 | `WalletServiceTest.concurrentConsentActivatesOnce...` | Cubierto. |

## HU-03 Medicion del onboarding

| Criterio | Tarea | Prueba / evidencia | Estado |
| --- | --- | --- | --- |
| Inicio, pasos y activacion distinguibles; sin valores de formulario | BE-01 | `OnboardingContractTest.eventsDistinguish...`, `FlowIntegrationTest.hu03...` | Cubierto. |
| `onboarding_exited` solo si la salida es observable | BE-01 | No existe tal evento; cierre, desconexion o expiracion no emiten nada | Cubierto; depende de D5. |
| Datos analiticos sin documentos, credenciales ni verificacion | BE-01, BE-05 | `OnboardingContractTest` (claves exactas del evento y privacidad) | Cubierto. |
| Duracion desde el primer inicio hasta la activacion, con reanudacion | BE-02 | `AnalyticsServiceTest.medianCoversResumedNotActivatedAndWindowBoundaryCases` | Cubierto. |
| Resumen mensual: mediana y conteo de activadas, sin llamarlo MAU | BE-03 | `AnalyticsServiceTest`, `FlowIntegrationTest.hu03...` | Cubierto. |
| Comparacion con 8 min / 3 min como objetivo | BE-02 | `targetEvaluation: NOT_EVALUATED` | Bloqueado por D6 (cohorte, lanzamiento, periodo). |
| MAU no disponible sin definicion o fuente | BE-03 | `AnalyticsServiceTest.mauAndCacAreUnavailable...`, `FlowIntegrationTest.hu03...` | Cubierto; bloqueado por D7. |
| CAC no disponible sin fuente, atribucion o denominador | BE-03, BE-04 | Idem; `roundsCacToTheNearestCent...`, `acquisitionCostsAcceptOnlyIntegerCents` | Cubierto; bloqueado por D8. Importes solo en centimos enteros. |
| Acceso administrativo y separacion demo/produccion | BE-05 | `OnboardingContractTest.adminEndpointsRequireTheAdministrativeKey`, `AnalyticsServiceTest.demoDataNeverFeeds...`, `ProductionModeApiTest.productionReportsNeverIncludeDemoData` | Cubierto con clave administrativa demo; autorizacion final depende de D5, D7, D8, D10. |

## Bloqueos abiertos (no resueltos por inferencia)

| Id | Decision pendiente | Impacto |
| --- | --- | --- |
| D2 | Mecanismo de registro, autenticacion y recuperacion | El token de posesion es solo demo. |
| D3 | Pasos, datos requeridos y reglas de Producto y Cumplimiento | Campos demo; DNI y celular no implementados. |
| D4 | Pasos y verificaciones que autorizan una wallet productiva | Produccion nunca activa. |
| D5 | Observabilidad fiable de la salida explicita y autorizacion de reportes | Sin `onboarding_exited`. |
| D6 | Cohorte, fecha de lanzamiento y periodo | Sin comparacion oficial con 8 y 3 minutos. |
| D7 | Definicion y fuente de MAU | MAU no disponible. |
| D8 | Fuente de gasto, atribucion y denominador de CAC | CAC no disponible. |
| D9 | Canal de entrega de metricas al frontend | HU-03-FE-01 pendiente. |
| D10 | Persistencia productiva | JSON local de una sola instancia. |

Pendientes que requieren el frontend (repo `front-grupo2`, todavia sin cliente): pruebas
integradas con UI de HU-01-INT-01, HU-02-FE y HU-03-FE-01, y la revision del OpenAPI contra
el cliente (QA-INT). El contrato OpenAPI esta listo en `/q/openapi` para esa revision.
