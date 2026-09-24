Perfecto, Eli. Aquí tienes el **README actualizado** para tu entorno con el servicio separado de apagado (`mc-poweroff.service`) y el timer que lo ejecuta automáticamente en la franja horaria que definiste (00:00–03:00).  

---

# AutoShutdown para Paper + apagado del host

## 1. Compilar el plugin

En una máquina con acceso a internet (Maven Central / repo de PaperMC):

```bash
cd autoshutdown-plugin
mvn clean package
```

Esto genera `target/paper-26.3-3.jar`. Antes de compilar:

- Ajusta la versión de `paper-api` en `pom.xml` para que coincida con la versión real de tu servidor Paper.
- Ajusta `api-version` en `src/main/resources/plugin.yml` igual.

Copia el jar resultante a:  
`/home/alexgg037/Minecraft-Java-Server/plugins/`

---

## 2. Configurar el plugin

Al arrancar una vez, se crea `plugins/AutoShutdown/config.yml`:

```yaml
start-hour: 0              # desde medianoche
end-hour: 3                # hasta las 3 AM
check-interval-minutes: 10 # cada cuánto revisa jugadores
empty-checks-required: 2   # revisiones vacías seguidas antes de iniciar la cuenta regresiva
grace-period-minutes: 5    # minutos de gracia antes de apagar de verdad; se cancela si alguien entra
```

**Tiempo de gracia:** una vez que el server lleva `empty-checks-required` revisiones vacío, arranca una cuenta regresiva de `grace-period-minutes`.  
- Si en ese tiempo se conecta un jugador, el apagado se cancela.  
- Si sigue vacío, crea el `shutdown.flag` y hace `Bukkit.shutdown()`.  
- El flag lo lee luego el servicio externo para decidir si apaga también la máquina.

---

## 3. Servicios systemd

### Servicio del servidor (puede seguir usando tu `minecraft-server.service` con `screen`):

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now minecraft-server.service
```

### Servicio de apagado del host (`mc-poweroff.service`):

Archivo `/etc/systemd/system/mc-poweroff.service`:

```ini
[Unit]
Description=Apagado automático de Raspberry por AutoShutdown
After=minecraft-server.service

[Service]
Type=oneshot
ExecStart=/usr/local/bin/mc-poweroff.sh
User=root

[Install]
WantedBy=multi-user.target
```

Script `/usr/local/bin/mc-poweroff.sh`:

```bash
#!/bin/bash
FLAG="/home/alexgg037/Minecraft-Java-Server/plugins/AutoShutdown/shutdown.flag"

if [ -f "$FLAG" ]; then
    rm -f "$FLAG"
    logger "AutoShutdown: sin jugadores, apagando la máquina host en 5s..."
    sleep 5
    /usr/sbin/poweroff
else
    logger "AutoShutdown: el servidor se detuvo manualmente, no se apaga el host."
fi
```

Instalación:

```bash
sudo chmod +x /usr/local/bin/mc-poweroff.sh
sudo systemctl daemon-reload
sudo systemctl enable mc-poweroff.service
```

---

## 4. Timer systemd

Archivo `/etc/systemd/system/mc-poweroff.timer`:

```ini
[Unit]
Description=Timer nocturno para mc-poweroff

[Timer]
# Corre cada 5 minutos entre las 00:00 y las 02:59
OnCalendar=*-*-* 00..04:00/5:00

[Install]
WantedBy=timers.target
```

Activación:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now mc-poweroff.timer
```

Ver próximas ejecuciones:

```bash
systemctl list-timers mc-poweroff.timer
```

---

## 5. Por qué es liviano

- El chequeo de jugadores vive dentro de la JVM del servidor (scheduler de Bukkit).  
- El script `mc-poweroff.sh` solo corre una vez cuando el servidor termina o cuando el timer lo invoca.  
- El apagado de la máquina ocurre únicamente si el plugin dejó el flag.  

---

## 6. Comandos útiles

```bash
sudo systemctl status minecraft-server
sudo systemctl stop minecraft-server      # apaga el server SIN apagar la máquina
sudo systemctl start mc-poweroff.service  # ejecuta el script de apagado manualmente
journalctl -u mc-poweroff.service -f      # ver logs del apagado
systemctl list-timers mc-poweroff.timer   # ver próximas ejecuciones del timer
```
