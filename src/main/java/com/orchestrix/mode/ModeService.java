package com.orchestrix.mode;

import com.orchestrix.config.OrchestrixProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the currently-active {@link ExecutionMode}. Default is taken from
 * {@code orchestrix.mode} in config; can be flipped at runtime via
 * {@link #setMode(ExecutionMode)} (exposed by the admin controller).
 *
 * Atomic reference is used so concurrent reads see a consistent value without
 * locking the request path.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModeService {

    private final OrchestrixProperties props;
    private final AtomicReference<ExecutionMode> current = new AtomicReference<>(ExecutionMode.HYBRID);

    @PostConstruct
    public void init() {
        ExecutionMode initial = ExecutionMode.parse(props.getMode(), ExecutionMode.HYBRID);
        current.set(initial);
        log.info("orchestrix-mode initialized mode={}", initial);
    }

    public ExecutionMode current() {
        return current.get();
    }

    public ExecutionMode setMode(ExecutionMode mode) {
        ExecutionMode previous = current.getAndSet(mode);
        if (previous != mode) {
            log.warn("orchestrix-mode switched from={} to={}", previous, mode);
        }
        return mode;
    }
}
