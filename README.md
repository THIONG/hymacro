# HyMacro - Hypixel Garden Automation Tool

![Python](https://img.shields.io/badge/python-v3.7+-blue.svg)
![License](https://img.shields.io/badge/license-MIT-green.svg)
![Platform](https://img.shields.io/badge/platform-Windows-lightgrey.svg)

## 📋 Descripción

HyMacro es una herramienta de automatización diseñada específicamente para el modo Garden de Hypixel Skyblock. Este macro permite automatizar la recolección de diferentes cultivos como cocoa beans, nether wart y cobblestone.

## ✨ Características

- **🌱 Múltiples tipos de cultivos**: Soporte para cocoa beans, nether wart y cobblestone
- **⌨️ Controles intuitivos**: Activación mediante teclas de función (F8, F9, F10)
- **🔄 Automatización completa**: Incluye movimiento, recolección y teletransporte automático
- **⚡ Optimizado**: Tiempos de espera ajustados para máxima eficiencia

## 🚀 Instalación

### Prerrequisitos

- Python 3.7 o superior
- Windows (requerido para las librerías de automatización)

### Pasos de instalación

1. **Clona o descarga el repositorio**:
   ```bash
   git clone https://github.com/THIONG/hymacro.git
   cd hymacro
   ```

2. **Instala las dependencias**:
   ```bash
   pip install -r requirements.txt
   ```

3. **Ejecuta el programa**:
   ```bash
   python main.py
   ```

## 🎮 Uso

### Configuración inicial

1. **Prepara tu garden en Hypixel**:
   - Asegúrate de tener acceso al Garden
   - Configura tus plots según el tipo de cultivo que quieras automatizar
   - Posiciónate en el punto de inicio adecuado

2. **Ejecuta HyMacro**:
   ```bash
   python main.py
   ```

### Controles

| Tecla | Función | Cultivo | Descripción |
|-------|---------|---------|-------------|
| **F8** | Cocoa Beans | 🍫 | Automatiza la recolección de cocoa beans con patrón optimizado |
| **F9** | Nether Wart | 🔥 | Automatiza la recolección de nether wart |
| **F10** | Cobblestone | 🪨 | Automatiza la minería de cobblestone (240 segundos por ciclo) |

### Patrones de movimiento

- **Cocoa Beans (F8)**: Patrón W→D→S→A con 8 recorridos por warp
- **Nether Wart (F9)**: Patrón W→D→W→A con 4 recorridos por warp
- **Cobblestone (F10)**: Movimiento continuo hacia adelante con auto-warp

## ⚙️ Configuración avanzada

### Tiempos de espera

- **Cocoa Beans**: 93ms entre acciones + 1s de espera adicional
- **Nether Wart**: 119ms entre acciones
- **Cobblestone**: 240 segundos de minería continua

### Personalización

Puedes modificar los parámetros en el código fuente:

```python
# Ejemplo para cocoa beans
self.realizar_conjunto_recorridos(['w', 'd', 's', 'a'], 8, True, 93)
#                                 [teclas]              recorridos, cocoa, timing
```

## 🛡️ Consideraciones de seguridad

### ⚠️ Advertencias importantes

- **Uso bajo tu propia responsabilidad**: Este macro automatiza acciones en Minecraft
- **Términos de servicio**: Asegúrate de cumplir con los términos de servicio de Hypixel
- **Detección**: Usa con moderación para evitar posibles detecciones automáticas
- **Supervisión**: Mantén supervisión mientras el macro está activo

### 🔒 Buenas prácticas

- No uses el macro durante períodos excesivamente largos
- Varía los tiempos de uso para parecer más natural
- Mantén el juego visible mientras el macro está activo
- Detén el macro si experimentas lag o problemas de conexión

## 🐛 Solución de problemas

### Problemas comunes

**El macro no responde a las teclas**:
- Asegúrate de que Minecraft esté en primer plano
- Verifica que las teclas F8-F10 no estén siendo usadas por otros programas
- Ejecuta Python como administrador si es necesario

**Movimientos incorrectos**:
- Calibra tu posición inicial en el garden
- Verifica que no hay lag de red significativo
- Ajusta los tiempos de espera si es necesario

**Errores de instalación**:
- Asegúrate de tener Python 3.7+
- Instala las dependencias con `pip install -r requirements.txt`
- En Windows, puede requerir permisos de administrador

## 📝 Licencia

Este proyecto está bajo la Licencia MIT. Ver el archivo `LICENSE` para más detalles.

---

**Nota**: Este proyecto es para fines educativos y de automatización personal. Úsalo responsablemente y respeta los términos de servicio de Hypixel.
