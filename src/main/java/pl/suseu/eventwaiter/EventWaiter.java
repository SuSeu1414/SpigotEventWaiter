/*
 * Copyright 2020 John Grosh (jagrosh) & Kaidan Gustave (TheMonitorLizard) & Szymon Witek (SuSeu)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package pl.suseu.eventwaiter;

import org.apache.commons.lang.Validate;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
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

public class EventWaiter {

    private JavaPlugin plugin;
    private Map<SimpleImmutableEntry<Class<?>, EventPriority>, Set<WaitingEvent>> waitingEvents;

    /**
     * Constructs an EventWaiter using the provided {@link org.bukkit.plugin.java.JavaPlugin Plugin}
     *
     * @param plugin plugin instance
     */
    public EventWaiter(JavaPlugin plugin) {
        this.plugin = plugin;
        this.waitingEvents = new ConcurrentHashMap<>();
    }

    /**
     * Starts listening to events of given types
     * This should be called in onEnable function with all events types you're going to wait for
     *
     * @param eventTypes array of event types to listen
     */
    @SafeVarargs
    public final void addEvents(Class<? extends Event>... eventTypes) {
        if (eventTypes == null || eventTypes.length == 0) {
            return;
        }
        for (Class<? extends Event> eventType : eventTypes) {
            if (eventType == null) {
                continue;
            }
            for (EventPriority priority : EventPriority.values()) {
                plugin.getServer().getPluginManager().registerEvent(eventType, new Listener() {
                        },
                        priority, (listener, event) -> checkEvent(event, priority), plugin);
            }
        }
    }

    /**
     * Waits an indefinite amount of time for an {@link org.bukkit.event.Event Event} that
     * returns {@code true} when tested with the provided {@link java.util.function.Predicate Predicate}.
     *
     * @param classType The {@link java.lang.Class} of the Event to wait for. Never null.
     * @param priority  The {@link org.bukkit.event.EventPriority} of the event
     * @param condition The Predicate to test when Events of the provided type are thrown. Never null.
     * @param action    The Consumer to perform an action when the condition Predicate returns {@code true}. Never null.
     * @param <T>       The type of Event to wait for.
     */
    public <T extends Event> void waitForEvent(Class<T> classType, EventPriority priority,
                                               Predicate<T> condition, Consumer<T> action) {
        waitForEvent(classType, priority, condition, action, -1, null);
    }

    /**
     * Waits a predetermined amount of time for an {@link org.bukkit.event.Event Event} that
     * returns {@code true} when tested with the provided {@link java.util.function.Predicate Predicate}.
     *
     * @param classType     The {@link java.lang.Class} of the Event to wait for. Never null.
     * @param priority      The {@link org.bukkit.event.EventPriority} of the event
     * @param condition     The Predicate to test when Events of the provided type are thrown. Never null.
     * @param action        The Consumer to perform an action when the condition Predicate returns {@code true}. Never null.
     * @param <T>           The type of Event to wait for.
     * @param timeout       The maximum amount of time to wait for, or {@code -1} if there is no timeout.
     * @param timeoutAction The Runnable to run if the time runs out before a correct Event is thrown, or
     *                      {@code null} if there is no action on timeout.
     */
    public <T extends Event> void waitForEvent(Class<T> classType, EventPriority priority,
                                               Predicate<T> condition, Consumer<T> action,
                                               long timeout, Runnable timeoutAction) {
        Validate.notNull(classType, "The provided class type is null");
        Validate.notNull(condition, "The provided condition predicate is null");
        Validate.notNull(action, "The provided action consumer is null");

        WaitingEvent we = new WaitingEvent<>(condition, action);
        Set<WaitingEvent> set = waitingEvents
                .computeIfAbsent(new SimpleImmutableEntry<>(classType, priority), c -> new HashSet<>());
        set.add(we);

        if (timeout > 0) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (set.remove(we) && timeoutAction != null) {
                    timeoutAction.run();
                }
            }, timeout);
        }
    }

    private void checkEvent(Event event, EventPriority priority) {
        Class clazz = event.getClass();

        SimpleImmutableEntry<Class, EventPriority> c = new SimpleImmutableEntry<>(clazz, priority);

        if (waitingEvents.containsKey(c)) {
            Set<WaitingEvent> set = waitingEvents.get(c);
            WaitingEvent[] toRemove = set.toArray(new WaitingEvent[0]);
            set.removeAll(Stream.of(toRemove).filter(i -> i.attempt(event)).collect(Collectors.toSet()));
        }
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