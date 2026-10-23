# QA-BE: suite backend

- Comando: `.\mvnw.cmd test -B -ntp` (salida completa en `qa_backend_mvnw_test.txt`).
- Resultado: **BUILD SUCCESS, 48 pruebas, 0 fallos, 0 errores** (ejecutado el 23 de octubre de 2026).
- Desglose: contrato y privacidad REST 8, flujos integrados 3, modos de verificacion 4
  (pendiente, rechazada, produccion x2), repositorio JSON 7, acceso 2, analitica 8, servicio de wallet 10, API base 6.
- Los mensajes `IllegalAccessError ... --add-opens java.base/java.lang` del final de la salida
  provienen de JBoss Threads con Java 24 o superior al cerrar Quarkus. No afectan los resultados y ya aparecian antes de estos cambios.
- Demo (`scripts/demo-onboarding.ps1`) ejecutada contra el JAR empaquetado: wallet ACTIVE, entrada MOCK,
  actividad idempotente y reporte con MAU/CAC no disponibles.
- Trazabilidad y bloqueos abiertos: `docs/matriz_trazabilidad.md`.
