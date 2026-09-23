#!/bin/bash
# Se ejecuta como ExecStopPost del servicio paper.service (con permisos de root
# gracias al "+" en la unidad systemd). Solo apaga la máquina si el servidor
# se detuvo por el plugin AutoShutdown (existe el flag), no si fue un
# "systemctl stop/restart" manual.
FLAG="/home/alexgg037/Minecraft-Java-Server/plugins/AutoShutdown/shutdown.flag"

if [ -f "$FLAG" ]; then
    rm -f "$FLAG"
    logger "AutoShutdown: sin jugadores, apagando la máquina host en 5s..."
    sleep 5
    /usr/sbin/poweroff
else
    logger "AutoShutdown: el servidor se detuvo manualmente, no se apaga el host."
fi