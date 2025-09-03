#!/usr/bin/env python3
"""
HyMacro - Hypixel Garden Automation Tool

Una herramienta de automatización para el modo Garden de Hypixel Skyblock.
Permite automatizar la recolección de cocoa beans, nether wart y cobblestone.

Autor: HyMacro Team
Licencia: MIT
Versión: 2.0.0
"""

import pyautogui
import keyboard
import time
import json
import logging
import sys
from pathlib import Path
from typing import Dict, List, Any, Optional

class ConfigManager:
    """Gestor de configuración para cargar y validar settings del macro."""
    
    def __init__(self, config_path: str = "config.json"):
        self.config_path = Path(config_path)
        self.config: Dict[str, Any] = {}
        self.load_config()
    
    def load_config(self) -> None:
        """Carga la configuración desde el archivo JSON."""
        try:
            if not self.config_path.exists():
                raise FileNotFoundError(f"Archivo de configuración no encontrado: {self.config_path}")
            
            with open(self.config_path, 'r', encoding='utf-8') as f:
                self.config = json.load(f)
            
            self._validate_config()
            logging.info("Configuración cargada exitosamente")
            
        except (json.JSONDecodeError, FileNotFoundError) as e:
            logging.error(f"Error cargando configuración: {e}")
            print(f"Error: No se pudo cargar la configuración. {e}")
            sys.exit(1)
    
    def _validate_config(self) -> None:
        """Valida que la configuración tenga los campos requeridos."""
        required_sections = ['macros', 'commands', 'keybinds', 'general']
        for section in required_sections:
            if section not in self.config:
                raise ValueError(f"Sección requerida '{section}' no encontrada en configuración")
    
    def get(self, *keys) -> Any:
        """Obtiene un valor de la configuración usando dot notation."""
        value = self.config
        for key in keys:
            if isinstance(value, dict) and key in value:
                value = value[key]
            else:
                return None
        return value


class MacroController:
    """Controlador principal para ejecutar los diferentes tipos de macros."""
    
    def __init__(self, config: ConfigManager):
        self.config = config
        self.is_running = False
        self._setup_logging()
        self._setup_pyautogui()
    
    def _setup_logging(self) -> None:
        """Configura el sistema de logging."""
        logging.basicConfig(
            level=logging.INFO,
            format='%(asctime)s - %(levelname)s - %(message)s',
            handlers=[
                logging.FileHandler('hymacro.log'),
                logging.StreamHandler()
            ]
        )
    
    def _setup_pyautogui(self) -> None:
        """Configura pyautogui con settings seguros."""
        pyautogui.FAILSAFE = True
        pyautogui.PAUSE = 0.01
    
    def execute_route(self, keys: List[str], use_cocoa_wait: bool, 
                     cocoa_wait_time: int, timing_ms: int) -> None:
        """Ejecuta una ruta de movimiento específica."""
        try:
            mouse_button = self.config.get('general', 'mouse_button')
            
            pyautogui.mouseDown(button=mouse_button)
            keyboard.press(keys[0])
            
            if use_cocoa_wait and cocoa_wait_time >= 1:
                time.sleep(cocoa_wait_time)
            
            keyboard.press(keys[1])
            time.sleep(timing_ms / 1000.0)  # Convertir ms a segundos
            
            pyautogui.mouseUp(button=mouse_button)
            keyboard.release(keys[0])
            keyboard.release(keys[1])
            
        except Exception as e:
            logging.error(f"Error ejecutando ruta: {e}")
    
    def execute_route_set(self, macro_type: str) -> None:
        """Ejecuta un conjunto completo de rutas para un tipo de macro."""
        try:
            macro_config = self.config.get('macros', macro_type)
            if not macro_config:
                raise ValueError(f"Configuración no encontrada para macro: {macro_type}")
            
            keys = macro_config['keys']
            routes_per_warp = macro_config['routes_per_warp']
            use_cocoa_wait = macro_config.get('use_cocoa_wait', False)
            timing_ms = macro_config['timing_ms']
            cocoa_wait = macro_config.get('cocoa_wait_seconds', 0)
            
            while self.is_running:
                # Ejecutar rutas
                for i in range(routes_per_warp):
                    if not self.is_running:
                        break
                    
                    # Primera ruta
                    self.execute_route(keys[0:2], use_cocoa_wait, cocoa_wait, timing_ms)
                    # Segunda ruta
                    self.execute_route(keys[2:4], use_cocoa_wait, 0, timing_ms)
                
                # Warp al garden
                if self.is_running:
                    self._send_command(self.config.get('commands', 'warp_garden'))
                
        except Exception as e:
            logging.error(f"Error en conjunto de rutas {macro_type}: {e}")
            self.stop_macro()
    
    def execute_cobblestone_route(self) -> None:
        """Ejecuta la ruta específica para cobblestone."""
        try:
            cobble_config = self.config.get('macros', 'cobblestone')
            key = cobble_config['key']
            duration = cobble_config['mining_duration_seconds']
            hub_wait = cobble_config['hub_wait_seconds']
            mouse_button = self.config.get('general', 'mouse_button')
            
            pyautogui.mouseDown(button=mouse_button)
            keyboard.press(key)
            time.sleep(duration)
            pyautogui.mouseUp(button=mouse_button)
            keyboard.release(key)
            
        except Exception as e:
            logging.error(f"Error en ruta de cobblestone: {e}")
    
    def execute_cobblestone_set(self) -> None:
        """Ejecuta el conjunto completo para cobblestone con warps."""
        try:
            hub_wait = self.config.get('macros', 'cobblestone', 'hub_wait_seconds')
            
            while self.is_running:
                self.execute_cobblestone_route()
                
                if self.is_running:
                    # Ir al hub
                    self._send_command(self.config.get('commands', 'warp_hub'))
                    time.sleep(hub_wait)
                    
                    # Volver a la isla
                    self._send_command(self.config.get('commands', 'warp_island'))
                
        except Exception as e:
            logging.error(f"Error en conjunto de cobblestone: {e}")
            self.stop_macro()
    
    def _send_command(self, command: str) -> None:
        """Envía un comando al chat del juego."""
        try:
            chat_key = self.config.get('general', 'chat_key')
            pyautogui.press(chat_key)
            pyautogui.write(command)
            pyautogui.press('enter')
        except Exception as e:
            logging.error(f"Error enviando comando '{command}': {e}")
    
    def start_macro(self, macro_type: str) -> None:
        """Inicia un macro específico."""
        if self.is_running:
            logging.warning("Ya hay un macro ejecutándose")
            return
        
        self.is_running = True
        logging.info(f"Iniciando macro: {macro_type}")
        
        try:
            if macro_type == 'cobblestone':
                self.execute_cobblestone_set()
            else:
                self.execute_route_set(macro_type)
        except Exception as e:
            logging.error(f"Error ejecutando macro {macro_type}: {e}")
            self.stop_macro()
    
    def stop_macro(self) -> None:
        """Detiene el macro actual."""
        if self.is_running:
            self.is_running = False
            logging.info("Macro detenido")


class HyMacroApp:
    """Aplicación principal que maneja la interfaz y los controles."""
    
    def __init__(self):
        self.config = ConfigManager()
        self.controller = MacroController(self.config)
        self.running = True
    
    def display_banner(self) -> None:
        """Muestra el banner ASCII de la aplicación."""
        banner = r'''
╔═══════════════════════════════════════════════════════════════════════════════╗
║     /$$   /$$           /$$      /$$                                          ║
║    | $$  | $$          | $$$    /$$$                                          ║
║    | $$  | $$ /$$   /$$| $$$$  /$$$$  /$$$$$$   /$$$$$$$  /$$$$$$  /$$$$$$    ║
║    | $$$$$$$$| $$  | $$| $$ $$/$$ $$ |____  $$ /$$_____/ /$$__  $$/$$__  $$   ║
║    | $$__  $$| $$  | $$| $$  $$$| $$  /$$$$$$$| $$      | $$  \__/ $$  \ $$   ║
║    | $$  | $$| $$  | $$| $$\  $ | $$ /$$__  $$| $$      | $$     | $$  | $$   ║
║    | $$  | $$|  $$$$$$$| $$ \/  | $$|  $$$$$$$|  $$$$$$$| $$     |  $$$$$$/   ║
║    |__/  |__/ \____  $$|__/     |__/ \_______/ \_______/|__/      \______/    ║
║               /$$  | $$|                                                      ║
║              |  $$$$$$/                                                       ║
║               \______/                                                        ║
║                                                                               ║
║        F8: Cocoa-Beans        F9: Nether Warth        F10: Cobblestone        ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
        '''
        print(banner)
        print("HyMacro v2.0.0 - Hypixel Garden Automation Tool")
        print("Presiona las teclas de función para activar los macros")
        print("Presiona Ctrl+C para salir\n")
    
    def handle_keybinds(self) -> None:
        """Maneja las teclas de función para activar macros."""
        keybinds = self.config.get('keybinds')
        
        try:
            if keyboard.is_pressed(keybinds['cocoa_beans']):
                if not self.controller.is_running:
                    print("🍫 Activando macro de Cocoa Beans...")
                    self.controller.start_macro('cocoa_beans')
            
            elif keyboard.is_pressed(keybinds['nether_wart']):
                if not self.controller.is_running:
                    print("🔥 Activando macro de Nether Wart...")
                    self.controller.start_macro('nether_wart')
            
            elif keyboard.is_pressed(keybinds['cobblestone']):
                if not self.controller.is_running:
                    print("🪨 Activando macro de Cobblestone...")
                    self.controller.start_macro('cobblestone')
                    
        except Exception as e:
            logging.error(f"Error manejando teclas: {e}")
    
    def run(self) -> None:
        """Ejecuta el bucle principal de la aplicación."""
        try:
            self.display_banner()
            
            loop_delay = self.config.get('general', 'loop_delay_ms', 100) / 1000.0
            
            while self.running:
                self.handle_keybinds()
                time.sleep(loop_delay)
                
        except KeyboardInterrupt:
            print("\n⏹️  Deteniendo HyMacro...")
            self.controller.stop_macro()
            self.running = False
        except Exception as e:
            logging.error(f"Error en bucle principal: {e}")
            print(f"Error crítico: {e}")
        finally:
            print("👋 ¡Gracias por usar HyMacro!")


def main() -> None:
    """Función principal de entrada."""
    try:
        app = HyMacroApp()
        app.run()
    except Exception as e:
        print(f"Error fatal: {e}")
        logging.error(f"Error fatal: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()