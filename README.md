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
| **F9** | Nether Wart — serpentina D→W→A→W, 4 idas y vueltas (8 filas) por warp |
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

### El menú

Al abrir HyMacro (doble clic al `.exe` o `uv run hymacro`) sale un menú:

```
    1) Arrancar el macro          teclas F8/F9/F10 para iniciar, F12 para parar
    2) Calibrar los tiempos       cronometro manual sobre tu propio plot
    3) Probar el movimiento       comprueba que el juego recibe las teclas
    4) Probar el chat             escribe el comando de warp sin enviarlo
    5) Ver la configuracion       ruta del config.json y teclas asignadas
    0) Salir

  (Enter = 1)
```

Enter a secas arranca el macro, que es lo habitual. Las demás opciones son las
herramientas de ajuste, que antes sólo existían como argumentos de terminal.

La ola de arcoíris del banner sigue corriendo mientras el menú está abierto, y los mensajes van en
color: verde al arrancar, amarillo al parar, rojo en los errores. Si la salida
está redirigida a un fichero o la terminal no admite ANSI, se imprime en texto
plano — nunca se cuelan secuencias de escape. Se respeta la variable de entorno
`NO_COLOR`, y `general.colors: "never"` lo apaga del todo.

### Línea de comandos

Todo lo del menú está también como flag, para quien prefiera la terminal:

```bash
uv run hymacro --check
```

| Flag | Descripción |
|------|-------------|
| `--config RUTA` | Usa otro `config.json` |
| `--check` | Valida la configuración y sale, sin registrar hotkeys |
| `--calibrate [MACRO]` | Cronómetro manual: mides tu recorrido con F12 y te da los tres tiempos |
| `--test-move [TECLA]` | Mantiene una tecla unos segundos, para ver si el juego la registra |
| `--test-chat` | Escribe el comando de warp en el chat sin enviarlo |
| `--no-menu` | Va directo al modo hotkeys, sin menú |
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
| `keys` | Las 4 teclas del patrón: `[ida, paso, vuelta, paso]` |
| `routes_per_warp` | **Idas y vueltas** antes del warp. `4` = 8 filas, no 4 |
| **`forward_seconds`** | **Segundos del tramo de ida (tecla 1). Es lo que te desplaza** |
| **`return_seconds`** | **Segundos del tramo de vuelta (tecla 3). `0` = un solo sentido** |
| `step_seconds` | Segundos del paso entre filas (teclas 2 y 4) |
| `mining_duration_seconds` | (cobblestone) Segundos de minado por ciclo |
| `hub_wait_seconds` | (cobblestone) Espera en el hub antes de volver a la isla |

> `forward_seconds` es el ajuste que más importa y el único que no se puede
> adivinar desde fuera: depende del tamaño de tu plot, tu velocidad y tus buffs.
> Si el macro "va muy rápido" y cambia de fila sin llegar al fondo, es este.
> Mídelo con `--calibrate` (ver abajo) en vez de probar a ojo.
>
> Los configs de la v2 usaban `use_cocoa_wait` + `cocoa_wait_seconds` para lo
> mismo, pero sólo en cocoa beans — por eso nether wart nunca avanzaba. Se
> siguen leyendo si no defines `forward_seconds`.

#### Los dos tipos de recorrido

**Serpentina** (nether wart) — miras al frente picando y te desplazas de lado.
El tramo largo es lateral y el paso entre filas es hacia delante:

```json
"keys": ["d", "w", "a", "w"],
"forward_seconds": 120,
"return_seconds": 120,
"step_seconds": 1.2
```

Recorre la fila hacia la derecha → paso adelante → recorre la siguiente hacia
la izquierda → paso adelante → y vuelta a empezar.

**Un solo sentido** (cocoa beans) — avanzas, corriges y repites. La vuelta no
tiene tramo largo, así que `return_seconds` se queda en `0`:

```json
"keys": ["w", "d", "s", "a"],
"forward_seconds": 1,
"return_seconds": 0
```

> En la v2 el tramo de vuelta estaba **fijado a 0 en el código**, así que la
> serpentina era imposible: por mucho que ajustaras los tiempos, la fila de
> vuelta duraba sólo `timing_ms`.

### Calibrar el recorrido

```bash
uv run hymacro --calibrate nether_wart
```

Es un **cronómetro manual**: el programa no mueve nada, conduces tú y él sólo
mide. Marcas cada tramo pulsando **F12** dos veces, al empezar y al acabar:

1. **La fila entera** — F12, recorres la fila de punta a punta, F12.
2. **El paso a la fila siguiente** — F12, pasas a la fila de al lado, F12.

Al no inyectar ninguna tecla, no hay riesgo de que el personaje se te vaya del
plot mientras calibras.

Al terminar te imprime el bloque listo para pegar en `config.json`:

```
    "forward_seconds": 120.4,
    "return_seconds": 120.4,
    "step_seconds": 1.20
```

### `general`

| Clave | Por defecto | Descripción |
|-------|-------------|-------------|
| `mouse_button` | `left` | Botón que se mantiene pulsado |
| `chat_key` | `t` | Tecla que abre el chat |
| `chat_open_delay_ms` | `120` | Espera antes de escribir el comando |
| `command_input_mode` | `unicode` | `unicode` o `scancode` (ver abajo) |
| `timing_jitter_ms` | `8` | Variación aleatoria de los pasos cortos, en ms |
| `wait_jitter_percent` | `5` | Variación aleatoria de las esperas largas, en % |
| `wait_jitter_max_seconds` | `0.5` | Tope de esa variación. Sin él, un 5% sobre una fila de 2 min serían ±6 s y te saltarías filas |
| `suppress_hotkeys` | `true` | Impide que F8–F12 lleguen también al juego |
| `colors` | `auto` | `auto` / `always` / `never`. En `auto` sólo pinta si hay consola de verdad |
| `banner_animation` | `true` | El banner se anima con una ola de arcoíris que corre hacia la derecha |
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
