package com.orthoflow.platform.events;

import com.orthoflow.common.events.LiveEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Pushes "something changed" to every open screen of a clinic so two
 * receptionists see the same agenda. Events carry no patient data — only a type
 * and the id of the record that changed — and the browser refetches through the
 * normal, permission-checked API.
 *
 * <p>SSE rather than WebSockets: one direction is all that is needed, it
 * crosses the existing reverse proxy without upgrade handling, and the browser
 * reconnects by itself. Per-instance and in memory; with several backend
 * instances this moves to a shared broker, and callers of {@link #publish} do
 * not change.
 */
@Component
public class LiveEvents implements LiveEventPublisher {

    private static final long TIMEOUT_MILLIS = 30 * 60_000L;

    private final Map<UUID, Set<SseEmitter>> emittersByPractice = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID practiceId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        Set<SseEmitter> set = emittersByPractice.computeIfAbsent(practiceId, id -> new CopyOnWriteArraySet<>());
        set.add(emitter);
        emitter.onCompletion(() -> set.remove(emitter));
        emitter.onTimeout(() -> set.remove(emitter));
        emitter.onError(e -> set.remove(emitter));
        try {
            emitter.send(SseEmitter.event().name("ready").data("ok"));
        } catch (IOException e) {
            set.remove(emitter);
        }
        return emitter;
    }

    /**
     * Publishes after the surrounding transaction commits, so a client that
     * refetches in response always sees the change. Outside a transaction it
     * publishes immediately.
     */
    @Override
    public void publish(UUID practiceId, String type, UUID entityId) {
        Runnable send = () -> broadcast(practiceId, type, entityId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    private void broadcast(UUID practiceId, String type, UUID entityId) {
        Set<SseEmitter> set = emittersByPractice.get(practiceId);
        if (set == null) {
            return;
        }
        String data = "{\"type\":\"" + type + "\",\"id\":" + (entityId == null ? "null" : "\"" + entityId + "\"") + "}";
        for (SseEmitter emitter : set) {
            try {
                emitter.send(SseEmitter.event().name("change").data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                set.remove(emitter);
            }
        }
    }

    /** A comment line keeps idle connections open through proxies that close silent ones. */
    @Scheduled(fixedDelay = 25_000)
    void heartbeat() {
        emittersByPractice.values().forEach(set -> set.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException e) {
                set.remove(emitter);
            }
        }));
    }
}
