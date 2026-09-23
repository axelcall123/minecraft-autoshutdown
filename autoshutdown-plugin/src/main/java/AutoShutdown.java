package com.example.autoshutdown;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.LocalTime;
import java.util.logging.Level;

/**
 * AutoShutdown
 * - A partir de "start-hour", revisa cada "check-interval-minutes" si hay jugadores online.
 * - Si el servidor está vacío durante "empty-checks-required" revisiones seguidas,
 *   entra en "cuenta regresiva" de "grace-period-minutes" antes de apagar de verdad.
 * - Si durante esa cuenta regresiva entra un jugador, el apagado se cancela solo.
 * - Al cumplirse la cuenta regresiva sin jugadores, crea un flag file y llama a
 *   Bukkit.shutdown() (apagado limpio, guarda mundos).
 * - El flag file lo lee luego un script externo (ExecStopPost de systemd) para decidir
 *   si apaga también la máquina física.
 *
 * Todo el chequeo vive dentro de la JVM del propio servidor: no hay ningún proceso
 * externo haciendo polling constante, por lo que el costo extra es prácticamente cero.
 */
public class AutoShutdown extends JavaPlugin implements Listener {

    private BukkitTask checkTask;
    private BukkitTask pendingShutdownTask;

    private int startHour;
    private int intervalMinutes;
    private int emptyChecksRequired;
    private int gracePeriodMinutes;

    private int emptyStreak = 0;
    private boolean shutdownScheduled = false;
    private File flagFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();

        startHour = getConfig().getInt("start-hour", 3);
        intervalMinutes = Math.max(1, getConfig().getInt("check-interval-minutes", 10));
        emptyChecksRequired = Math.max(1, getConfig().getInt("empty-checks-required", 2));
        gracePeriodMinutes = Math.max(0, getConfig().getInt("grace-period-minutes", 5));

        flagFile = new File(getDataFolder(), "shutdown.flag");
        // Por si quedó un flag de una ejecución anterior sin limpiar.
        if (flagFile.exists()) {
            flagFile.delete();
        }

        Bukkit.getPluginManager().registerEvents(this, this);

        long periodTicks = intervalMinutes * 60L * 20L; // minutos -> ticks (20 ticks/seg)
        checkTask = Bukkit.getScheduler().runTaskTimer(this, this::checkAndMaybeSchedule, periodTicks, periodTicks);

        getLogger().info("AutoShutdown activo: desde las " + startHour + ":00, revisando cada "
                + intervalMinutes + " min, apaga tras " + emptyChecksRequired
                + " revisiones vacías + " + gracePeriodMinutes + " min de gracia.");
    }

    @Override
    public void onDisable() {
        if (checkTask != null) {
            checkTask.cancel();
        }
        if (pendingShutdownTask != null) {
            pendingShutdownTask.cancel();
        }
    }

    /**
     * Si un jugador entra mientras hay un apagado en cuenta regresiva, se cancela.
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        emptyStreak = 0;
        if (shutdownScheduled) {
            cancelPendingShutdown();
            getLogger().info("Apagado cancelado: " + event.getPlayer().getName() + " se conectó.");
        }
    }

    private void checkAndMaybeSchedule() {
        int hourNow = LocalTime.now().getHour();

        if (!isWithinWindow(hourNow)) {
            emptyStreak = 0;
            return;
        }

        if (shutdownScheduled) {
            // Ya está en cuenta regresiva, no hace falta seguir contando revisiones.
            return;
        }

        if (Bukkit.getOnlinePlayers().isEmpty()) {
            emptyStreak++;
            getLogger().info("Servidor vacío (" + emptyStreak + "/" + emptyChecksRequired + ").");

            if (emptyStreak >= emptyChecksRequired) {
                scheduleShutdown();
            }
        } else {
            emptyStreak = 0;
        }
    }

    /**
     * En vez de apagar de inmediato, arranca una cuenta regresiva de
     * "grace-period-minutes". Si en ese tiempo entra alguien, se cancela
     * (ver onPlayerJoin). Si sigue vacío, apaga de verdad al final.
     */
    private void scheduleShutdown() {
        shutdownScheduled = true;

        if (gracePeriodMinutes <= 0) {
            doShutdown();
            return;
        }

        getLogger().warning("Sin jugadores. Apagando en " + gracePeriodMinutes
                + " minuto(s) si nadie se conecta...");
        Bukkit.broadcastMessage("[AutoShutdown] Servidor vacío. Se apagará en "
                + gracePeriodMinutes + " min si no entra nadie.");

        long delayTicks = gracePeriodMinutes * 60L * 20L;
        pendingShutdownTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (Bukkit.getOnlinePlayers().isEmpty()) {
                doShutdown();
            } else {
                // Seguridad extra por si el evento de join no alcanzó a cancelar a tiempo.
                cancelPendingShutdown();
            }
        }, delayTicks);
    }

    private void cancelPendingShutdown() {
        shutdownScheduled = false;
        emptyStreak = 0;
        if (pendingShutdownTask != null) {
            pendingShutdownTask.cancel();
            pendingShutdownTask = null;
        }
    }

    private void doShutdown() {
        getLogger().warning("Tiempo de gracia agotado sin jugadores. Apagando el servidor...");
        writeFlag();
        Bukkit.shutdown();
    }

    /**
     * Ventana simple: cualquier hora >= start-hour (mismo día).
     * Si prefieres una ventana nocturna cíclica (ej. 23:00 a 07:00), reemplaza por:
     *
     *   int endHour = getConfig().getInt("end-hour", 7);
     *   if (startHour <= endHour) {
     *       return hourNow >= startHour && hourNow < endHour;
     *   } else {
     *       return hourNow >= startHour || hourNow < endHour;
     *   }
     */
    private boolean isWithinWindow(int hourNow) {
        int endHour = getConfig().getInt("end-hour", 7);

        if (startHour <= endHour) {
            // ventana normal (ej. 2 → 7)
            return hourNow >= startHour && hourNow < endHour;
        } else {
            // ventana que cruza medianoche (ej. 23 → 7)
            return hourNow >= startHour || hourNow < endHour;
        }
    }


    private void writeFlag() {
        try {
            File parent = flagFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            if (!flagFile.exists()) {
                flagFile.createNewFile();
            }
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "No se pudo crear el flag de apagado", e);
        }
    }
}