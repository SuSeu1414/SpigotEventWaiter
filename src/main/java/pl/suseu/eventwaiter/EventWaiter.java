package pl.suseu.eventwaiter;

import org.apache.commons.lang.Validate;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class EventWaiter implements Listener {

    private JavaPlugin plugin;
    private Map<SimpleImmutableEntry<Class<?>, EventPriority>, Set<WaitingEvent>> waitingEvents;

    public EventWaiter(JavaPlugin plugin) {
        this.plugin = plugin;
        this.waitingEvents = new ConcurrentHashMap<>();
    }

    public <T extends Event> void waitForEvent(Class<T> classType, EventPriority priority,
                                               Predicate<T> condition, Consumer<T> action) {
        waitForEvent(classType, priority, condition, action, -1, null);
    }

    public <T extends Event> void waitForEvent(Class<T> classType, EventPriority priority,
                                               Predicate<T> condition, Consumer<T> action,
                                               long timeout, Runnable timeoutAction) {
        Validate.notNull(classType, "The provided class type");
        Validate.notNull(condition, "The provided condition predicate");
        Validate.notNull(action, "The provided action consumer");

        WaitingEvent we = new WaitingEvent<>(condition, action);
        Set<WaitingEvent> set = waitingEvents
                .computeIfAbsent(new SimpleImmutableEntry<>(classType, priority), c -> new HashSet<>());
        set.add(we);

        if(timeout > 0) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if(set.remove(we) && timeoutAction != null) {
                    timeoutAction.run();
                }
            }, timeout);
        }
    }

    public void checkEvent(Event event, EventPriority priority) {
        Class clazz = event.getClass();

        SimpleImmutableEntry<Class, EventPriority> c = new SimpleImmutableEntry<>(clazz, priority);

        if(waitingEvents.containsKey(c)) {
            Set<WaitingEvent> set = waitingEvents.get(c);
            WaitingEvent[] toRemove = set.toArray(new WaitingEvent[0]);
            set.removeAll(Stream.of(toRemove).filter(i -> i.attempt(event)).collect(Collectors.toSet()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockBreakMonitor(BlockBreakEvent event) {
        checkEvent(event, EventPriority.MONITOR);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        checkEvent(event, EventPriority.NORMAL);
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        checkEvent(event, EventPriority.NORMAL);
    }

    private class WaitingEvent<T extends Event> {
        final Predicate<T> condition;
        final Consumer<T> action;

        WaitingEvent(Predicate<T> condition, Consumer<T> action) {
            this.condition = condition;
            this.action = action;
        }

        boolean attempt(T event) {
            if (condition.test(event)) {
                action.accept(event);
                return true;
            }
            return false;
        }
    }
}
