package com.example.autoshutdown;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.logging.Level;

/**
 * AutoShutdown (event-driven)
 * - Ya NO hace polling cada N minutos. En vez de eso:
 *   1) Cuando un jugador se desconecta (PlayerQuitEvent), revisa 1 tick después
 *      si el servidor quedó vacío y si estamos dentro de la ventana horaria
 *      [start-hour, end-hour). Si sí, arranca la cuenta regresiva de apagado.
 *   2) Si un jugador entra (PlayerJoinEvent) durante la cuenta regresiva, se cancela.
 *   3) Una única tarea que se reprograma sola para dispararse exactamente a
 *      "start-hour" cada día, para cubrir el caso borde en que el servidor
 *      YA estaba vacío antes de que empezara la ventana (ahí no hay ningún
 *      evento de quit que dispare la revisión).
 *
 * Resultado: cero sondeo periódico redundante, todo reacciona a eventos reales
 * o a un único disparo diario calculado con delay exacto.
 */
public class AutoShutdown extends JavaPlugin implements Listener {

    private BukkitTask pendingShutdownTask;
    private BukkitTask dailyWindowCheckTask;

    private int startHour;
    private int endHour;
    private int gracePeriodMinutes;

    private boolean shutdownScheduled = false;
    private File flagFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();

        startHour = getConfig().getInt("start-hour", 3);
        endHour = getConfig().getInt("end-hour", 7);
        gracePeriodMinutes = Math.max(0, getConfig().getInt("grace-period-minutes", 5));

        flagFile = new File(getDataFolder(), "shutdown.flag");
        // Por si quedó un flag de una ejecución anterior sin limpiar.
        if (flagFile.exists()) {
            flagFile.delete();
        }

        Bukkit.getPluginManager().registerEvents(this, this);

        scheduleDailyWindowCheck();

        getLogger().info("AutoShutdown activo (event-driven): ventana " + startHour + ":00-"
                + endHour + ":00, apaga tras " + gracePeriodMinutes
                + " min de gracia sin jugadores.");
    }

    @Override
    public void onDisable() {
        if (dailyWindowCheckTask != null) {
            dailyWindowCheckTask.cancel();
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
        if (shutdownScheduled) {
            cancelPendingShutdown();
            getLogger().info("Apagado cancelado: " + event.getPlayer().getName() + " se conectó.");
        }
    }

    /**
     * Revisa al desconectarse un jugador si el servidor quedó vacío.
     * Se agenda 1 tick después porque durante el propio evento a veces el
     * jugador que se va todavía cuenta en getOnlinePlayers() dependiendo
     * del server/version.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Bukkit.getScheduler().runTask(this, this::checkIfShouldSchedule);
    }

    /**
     * Disparo único que se reprograma solo para la próxima "start-hour".
     * Cubre el caso borde: servidor ya vacío cuando empieza la ventana.
     */
    private void scheduleDailyWindowCheck() {
        long delayTicks = ticksUntilNext(startHour);
        dailyWindowCheckTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            checkIfShouldSchedule();
            scheduleDailyWindowCheck(); // reprograma para el día siguiente
        }, delayTicks);
    }

    private long ticksUntilNext(int hour) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = now.withHour(hour).withMinute(0).withSecond(0).withNano(0);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        long seconds = ChronoUnit.SECONDS.between(now, next);
        return seconds * 20L; // segundos -> ticks
    }

    private void checkIfShouldSchedule() {
        int hourNow = LocalTime.now().getHour();

        if (!isWithinWindow(hourNow)) {
            return;
        }

        if (shutdownScheduled) {
            // Ya está en cuenta regresiva, no hace falta hacer nada más.
            return;
        }

        if (Bukkit.getOnlinePlayers().isEmpty()) {
            scheduleShutdown();
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
     * Ventana horaria: soporta tanto rango normal (2 -> 7) como rango
     * que cruza medianoche (23 -> 7).
     */
    private boolean isWithinWindow(int hourNow) {
        if (startHour <= endHour) {
            return hourNow >= startHour && hourNow < endHour;
        } else {
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
