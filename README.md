# LEADEN2 APK V1.4 — Switch y audio orden.mp3

Esta versión parte del código fuente V1.3, que ya fue probado por el usuario y conserva el registro local, los contactos, el reconocimiento de voz, la comunicación con el webhook LEADEN2 y la lectura hablada de las respuestas. No modifica la PWA, Nginx, la base de datos de licencias ni los workflows de LEADEN1.

## Cambios de V1.4

- Actualiza la presentación visual a una interfaz futurista inspirada en HUD: fondo oscuro, paneles de control, acentos cian y secciones organizadas.
- Añade el botón final «SALIR DE LEADEN2». Al pulsarlo, pone el switch en INACTIVO, guarda `assistant_enabled=false`, ordena detener los servicios y cierra la actividad.

- Sustituye el botón «ACTIVAR LEADEN2» por un switch para activar y desactivar el asistente.
- El estado del switch se guarda en las preferencias locales del teléfono (`assistant_enabled`).
- Al desactivar, se detienen los servicios de voz y reproducción, y se vacía el audio en curso.
- El botón «PROBAR RECONOCIMIENTO DE VOZ» queda deshabilitado mientras el switch esté apagado.
- El audio que se reproduce al presionar el botón central de las gafas ahora es `app/src/main/assets/orden.mp3`, integrado dentro del APK. No depende de la unidad D:.
- Conserva el audio de activación inicial `comienza_el_ensamblaje.mp3`.
- Conserva la lectura de respuestas del webhook en formato `{"texto":"..."}` o `[{"output":"..."}]`.

## Funciones conservadas

- Perfil local: nombre, cédula, celular y correo.
- Agenda local: nombre y teléfono. Mantén pulsada la lista para eliminar un contacto.
- Sin validación de licencias en esta etapa.
- Webhook: `https://n8n.serviapps.duckdns.org/webhook/asistente-leaden2`.
- Payload común: `nombre`, `cedula`, `celular`, `correo` y `text`.
- Si “análisis” aparece entre las tres primeras palabras, añade `"analisis": "analisis"`.
- Si “mensaje” aparece entre las tres primeras palabras, añade `contactos` como arreglo con `nombre` y `telefono`.
- Los datos personales y el payload completo no se imprimen en Logcat.

## Compilación con GitHub Actions

1. Sube el contenido de esta carpeta a un repositorio independiente de la V1.2 (o a una rama nueva dedicada a V1.4).
2. Abre **Actions** y ejecuta **Build APK LEADEN2 V1.4**.
3. Descarga el artifact `leaden2-app-v1-4-jarvis-interface`.
4. Instala `app-debug.apk` en el Redmi 12C y prueba antes de sustituir la versión estable.

La compilación debe confirmarse en GitHub Actions; no se afirma que el APK esté compilado hasta que el workflow termine correctamente.

## Pruebas recomendadas

1. Instala la V1.4 y verifica que el perfil y los contactos se conserven si Android actualiza la misma instalación; al instalar con datos borrados o como instalación nueva, registra los datos de nuevo.
2. Activa el switch y concede el permiso del micrófono.
3. Pulsa el botón central de las gafas: debe reproducirse `orden.mp3` y después comenzar el reconocimiento.
4. Confirma que n8n recibe el texto y que la respuesta se escucha.
5. Apaga el switch: el servicio debe detenerse y el botón de prueba quedar deshabilitado.
6. Vuelve a activar el switch y repite la prueba con la pantalla encendida y bloqueada.
7. Con el asistente activo, pulsa «SALIR DE LEADEN2» y confirma que la aplicación se cierra y el switch queda guardado como INACTIVO al volver a abrirla.
8. Comprueba que el ahorro de batería de HyperOS esté en «Sin restricciones» para LEADEN2, como en la V1.2 funcional.

## Configuración

- Paquete: `com.leaden2.app`
- `versionCode`: 4
- `versionName`: 1.4
- `compileSdk`: 35
- `targetSdk`: 35
- `minSdk`: 23
- JDK: 17
- Media3: 1.5.1


## Interfaz V1.4

- Interfaz visual futurista con fondo oscuro, paneles cian y controles organizados.
- Botón final **SALIR DE LEADEN2**: deja `assistant_enabled=false`, desactiva el servicio de voz y ordena detener el servicio de reproducción antes de cerrar la pantalla.
- Se conservan las funciones de voz, `orden.mp3`, contactos locales y webhook LEADEN2.
- La V1.4 no incorpora licencias; esa etapa queda reservada para V1.5.
