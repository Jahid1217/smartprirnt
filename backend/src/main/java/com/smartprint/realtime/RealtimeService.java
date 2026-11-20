package com.smartprint.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server-Sent Events fan-out. Clients receive a lightweight "update" event and
 * re-fetch the authoritative snapshot, so a missed event can never corrupt state.
 */
@Service
public class RealtimeService {
    private static final Logger log = LoggerFactory.getLogger(RealtimeService.class);
    private static final long TIMEOUT_MS = 0L;

    private final Map<UUID, List<SseEmitter>> orderListeners = new ConcurrentHashMap<>();
    private final Map<Long, List<SseEmitter>> shopListeners = new ConcurrentHashMap<>();

    public SseEmitter subscribeOrder(UUID publicToken) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        register(orderListeners.computeIfAbsent(publicToken, key -> new CopyOnWriteArrayList<>()),
            emitter, () -> remove(orderListeners, publicToken, emitter));
        return emitter;
    }

    public SseEmitter subscribeShop(Long shopId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        register(shopListeners.computeIfAbsent(shopId, key -> new CopyOnWriteArrayList<>()),
            emitter, () -> remove(shopListeners, shopId, emitter));
        return emitter;
    }

    public void orderChanged(UUID publicToken) {
        afterCommit(() -> broadcast(orderListeners.get(publicToken)));
    }

    public void shopChanged(Long shopId) {
        afterCommit(() -> broadcast(shopListeners.get(shopId)));
    }

    private void afterCommit(Runnable publish) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publish.run();
            }
        });
    }

    @Scheduled(fixedDelayString = "${smartprint.realtime.heartbeat-ms:20000}")
    public void heartbeat() {
        orderListeners.values().forEach(this::comment);
        shopListeners.values().forEach(this::comment);
    }

    private void register(List<SseEmitter> listeners, SseEmitter emitter, Runnable cleanup) {
        listeners.add(emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        try {
            emitter.send(SseEmitter.event().name("ready").data(Map.of("connected", true)));
        } catch (IOException ex) {
            cleanup.run();
        }
    }

    private void remove(Map<?, List<SseEmitter>> map, Object key, SseEmitter emitter) {
        List<SseEmitter> listeners = map.get(key);
        if (listeners == null) return;
        listeners.remove(emitter);
        if (listeners.isEmpty()) map.remove(key);
    }

    private void broadcast(List<SseEmitter> listeners) {
        if (listeners == null || listeners.isEmpty()) return;
        for (SseEmitter emitter : listeners) {
            try {
                emitter.send(SseEmitter.event().name("update").data(Map.of("changed", true)));
            } catch (Exception ex) {
                listeners.remove(emitter);
                log.debug("Dropped closed SSE listener: {}", ex.getMessage());
            }
        }
    }

    private void comment(List<SseEmitter> listeners) {
        for (SseEmitter emitter : listeners) {
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (Exception ex) {
                listeners.remove(emitter);
            }
        }
    }
}
