package pl.suseu.eventwaiter;

import org.apache.commons.lang.Validate;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class EventWaiter implements Listener {

    private Map<Class<?>, Set<WaitingEvent>> waitingEvents;
    private final ScheduledExecutorService threadpool;

    public EventWaiter() {
        this(Executors.newSingleThreadScheduledExecutor(), true);
    }

    public EventWaiter(ScheduledExecutorService threadpool, boolean shutdownAutomatically) {
        Validate.notNull(threadpool, "ScheduledExecutorService");
        Validate.isTrue(!threadpool.isShutdown(), "Cannot construct EventWaiter with a closed ScheduledExecutorService!");

        this.waitingEvents = new ConcurrentHashMap<>();
        this.threadpool = threadpool;
    }

    public boolean isShutdown() {
        return threadpool.isShutdown();
    }

    public <T extends Event> void waitForEvent(Class<T> classType, Predicate<T> condition, Consumer<T> action) {
        waitForEvent(classType, condition, action, -1, null, null);
    }

    public <T extends Event> void waitForEvent(Class<T> classType, Predicate<T> condition, Consumer<T> action,
                                               long timeout, TimeUnit unit, Runnable timeoutAction) {
        Validate.isTrue(!isShutdown(), "Attempted to register a WaitingEvent while the EventWaiter's threadpool was already shut down!");
        Validate.notNull(classType, "The provided class type");
        Validate.notNull(condition, "The provided condition predicate");
        Validate.notNull(action, "The provided action consumer");

        WaitingEvent we = new WaitingEvent<>(condition, action);
        Set<WaitingEvent> set = waitingEvents.computeIfAbsent(classType, c -> new HashSet<>());
        set.add(we);

        if(timeout > 0 && unit != null) {
            threadpool.schedule(() -> {
                if(set.remove(we) && timeoutAction != null) {
                    timeoutAction.run();
                }
            }, timeout, unit);
        }
    }

    public void checkEvent(Event event) {
        Class c = event.getClass();

        if(waitingEvents.containsKey(c)) {
            Set<WaitingEvent> set = waitingEvents.get(c);
            WaitingEvent[] toRemove = set.toArray(new WaitingEvent[0]);
            set.removeAll(Stream.of(toRemove).filter(i -> i.attempt(event)).collect(Collectors.toSet()));
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        checkEvent(event);
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        checkEvent(event);
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
