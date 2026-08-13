# HyMacro - Hypixel Garden Automation Tool

![Python](https://img.shields.io/badge/python-3.11+-blue.svg)
![License](https://img.shields.io/badge/license-MIT-green.svg)
![Platform](https://img.shields.io/badge/platform-Windows-lightgrey.svg)
[![CI](https://github.com/THIONG/hymacro/actions/workflows/ci.yml/badge.svg)](https://github.com/THIONG/hymacro/actions/workflows/ci.yml)

## Descripción

HyMacro automatiza la recolección en el modo Garden de Hypixel Skyblock: cocoa
beans, nether wart y cobblestone.

## Novedades de la v3

| | v2 | v3 |
|---|---|---|
| **Parar el macro** | solo `Ctrl+C` en la consola | hotkey **F12**, responde al instante |
| **Ejecución** | bloqueaba el hilo principal | hilo worker + watchdog |
| **Alt+Tab** | seguía escribiendo en otras ventanas | se detiene solo |
| **Entrada** | pyautogui (virtual-keys) | `SendInput` con scancodes |
| **Timings** | fijos | con variación aleatoria configurable |
| **Distribución** | clonar el repo | `.exe` en Releases |
| **Dependencias** | pyautogui + keyboard | solo `keyboard` |

Además se corrigieron cuatro bugs de la v2, entre ellos un `TypeError` al
arrancar que impedía que el bucle principal llegara a ejecutarse.

## Instalación

### Opción A: descargar el ejecutable (recomendado)

Baja el `.zip` de la [última release](https://github.com/THIONG/hymacro/releases),
descomprime y ejecuta `HyMacro.exe`. No necesitas Python.

> **SmartScreen y antivirus**: el ejecutable no está firmado digitalmente, así que
> Windows mostrará un aviso ("Más información" → "Ejecutar de todas formas"). Tu
> antivirus también puede marcarlo: un programa que engancha el teclado global se
> parece mucho a un keylogger para las heurísticas. Es un falso positivo — el
> código está entero en este repo y el binario se construye públicamente con
> [`release.yml`](.github/workflows/release.yml). Cada release incluye un
> `.sha256` para verificar la descarga.

### Opción B: desde el código con [uv](https://docs.astral.sh/uv/)

```bash
git clone https://github.com/THIONG/hymacro.git
```

```bash
cd hymacro && uv run hymacro
```

`uv` se encarga de instalar Python 3.12 y las dependencias. También funciona
`python main.py` si ya tienes un entorno preparado.

## Uso

### Controles

| Tecla | Acción |
|-------|--------|
| **F8** | Cocoa Beans — patrón W→D→S→A, 8 recorridos por warp |
| **F9** | Nether Wart — patrón W→D→W→A, 4 recorridos por warp |
| **F10** | Cobblestone — minado continuo con ciclo hub → isla |
| **F12** | **DETENER** el macro en marcha |
| **Ctrl+C** | Salir de HyMacro |

Al detenerse (por hotkey, por failsafe o al salir), HyMacro suelta todas las
teclas y botones que tenía presionados y te imprime las estadísticas de la sesión.

### Failsafes

El macro se detiene solo cuando:

- **Minecraft pierde el foco** — evita que `/warp garden` acabe en tu Discord.
- **Mueves el ratón** más de N píxeles — tu intervención manda.
- Se alcanza el **límite de sesión**, si lo configuras.

El watchdog los comprueba cada 100 ms, también durante los 4 minutos de minado
de cobblestone.

### Línea de comandos

```bash
uv run hymacro --check
```

| Flag | Descripción |
|------|-------------|
| `--config RUTA` | Usa otro `config.json` |
| `--check` | Valida la configuración y sale, sin registrar hotkeys |
| `--verbose` | Muestra los logs de debug en consola |
| `--version` | Muestra la versión |

También se respeta la variable de entorno `HYMACRO_CONFIG`.

## Configuración

Todo se edita en `config.json`, junto al ejecutable. Si lo borras se regenera
con los valores por defecto. Un `config.json` de la v2 sigue siendo válido: las
claves nuevas se heredan automáticamente.

### `macros`

| Clave | Descripción |
|-------|-------------|
| `keys` | Las 4 teclas del patrón (dos tramos de dos) |
| `routes_per_warp` | Recorridos antes de hacer warp al garden |
| `timing_ms` | Milisegundos entre las dos teclas de un tramo |
| `use_cocoa_wait` / `cocoa_wait_seconds` | Espera extra al inicio del primer tramo |
| `mining_duration_seconds` | (cobblestone) Segundos de minado por ciclo |
| `hub_wait_seconds` | (cobblestone) Espera en el hub antes de volver a la isla |

### `general`

| Clave | Por defecto | Descripción |
|-------|-------------|-------------|
| `mouse_button` | `left` | Botón que se mantiene pulsado |
| `chat_key` | `t` | Tecla que abre el chat |
| `chat_open_delay_ms` | `120` | Espera antes de escribir el comando |
| `command_input_mode` | `unicode` | `unicode` o `scancode` (ver abajo) |
| `timing_jitter_ms` | `8` | Variación aleatoria de los timings, en ms |
| `wait_jitter_percent` | `5` | Variación aleatoria de las esperas largas, en % |
| `suppress_hotkeys` | `true` | Impide que F8–F12 lleguen también al juego |
| `loop_delay_ms` | `100` | Periodo del bucle inactivo |

> Si los comandos llegan al chat cortados o no llegan, prueba a subir
> `chat_open_delay_ms` o a cambiar `command_input_mode` a `scancode`.

### `safety`

| Clave | Por defecto | Descripción |
|-------|-------------|-------------|
| `require_window_focus` | `true` | Parar si la ventana activa no es Minecraft |
| `window_title_contains` | `Minecraft` | Texto a buscar en el título de la ventana |
| `mouse_failsafe` | `true` | Parar si mueves el ratón |
| `mouse_failsafe_px` | `100` | Umbral del failsafe de ratón |
| `max_session_minutes` | `0` | Límite de sesión (`0` = sin límite) |
| `watchdog_interval_ms` | `100` | Frecuencia de comprobación de los failsafes |

### `keybinds` y `commands`

`keybinds` acepta cualquier nombre de tecla soportado (`f8`, `f12`, `home`...);
no puede haber dos iguales. `commands` define los comandos de chat
(`/warp garden`, `/hub`, `/is`).

## Desarrollo

```bash
uv sync --all-groups
```

| Comando | Qué hace |
|---------|----------|
| `uv run ruff check .` | Lint |
| `uv run ruff format .` | Formato |
| `uv run mypy` | Tipos (en modo estricto) |
| `uv run pytest` | Tests |
| `uv run pyinstaller packaging/hymacro.spec --noconfirm` | Construir el `.exe` |

### Estructura

```
src/hymacro/
  winput.py      SendInput por ctypes: scancodes, ratón, foco de ventana
  config.py      Carga, validación y resolución de rutas
  safety.py      Watchdog y failsafes
  controller.py  Bucles de macro en hilo worker + estadísticas
  app.py         Consola y hotkeys globales
packaging/       Receta de PyInstaller
```

### Publicar una release

Sube la versión en `pyproject.toml` y `src/hymacro/__init__.py`, y empuja el tag:

```bash
git tag v3.0.0 && git push origin v3.0.0
```

El workflow verifica que el tag coincida con la versión del paquete, construye
el `.exe`, comprueba que arranca, genera el SHA256 y publica la release.

## Solución de problemas

**Los hotkeys no responden** — ejecuta HyMacro como administrador. Los hooks
globales de teclado no pueden interceptar entrada dirigida a procesos elevados.

**El macro no arranca y dice que la ventana activa no es Minecraft** — el
failsafe de foco. Pulsa F8 con Minecraft en primer plano, o ajusta
`safety.window_title_contains` al título real de tu launcher.

**Se detiene solo a los pocos segundos** — casi siempre es el failsafe de ratón.
Sube `safety.mouse_failsafe_px` o ponlo en `false`.

**Los movimientos se desincronizan** — ajusta `timing_ms` para tu ping y
recalibra la posición inicial en el garden.

Los logs completos están en `hymacro.log`, junto al ejecutable.

## Consideraciones

- **Úsalo bajo tu propia responsabilidad.** Automatizar acciones puede ir contra
  las reglas de Hypixel; revísalas antes.
- Mantén supervisión mientras el macro esté activo.
- Los failsafes reducen accidentes, pero no sustituyen estar pendiente.

## Licencia

MIT. Ver [LICENSE](LICENSE).
