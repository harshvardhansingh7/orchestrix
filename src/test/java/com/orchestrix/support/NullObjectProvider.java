package com.orchestrix.support;

import org.springframework.beans.factory.ObjectProvider;

import java.util.Iterator;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * ObjectProvider that resolves to nothing — for tests that pass through code
 * paths which look up an optional bean and tolerate its absence.
 */
public class NullObjectProvider<T> implements ObjectProvider<T> {

    @Override
    public T getObject() {
        throw new IllegalStateException("missing-test-bean");
    }

    @Override
    public T getObject(Object... args) {
        throw new IllegalStateException("missing-test-bean");
    }

    @Override
    public T getIfAvailable() {
        return null;
    }

    @Override
    public T getIfUnique() {
        return null;
    }

    @Override
    public Iterator<T> iterator() {
        return java.util.Collections.emptyIterator();
    }

    @Override
    public Stream<T> stream() {
        return Stream.empty();
    }

    @Override
    public Stream<T> orderedStream() {
        return Stream.empty();
    }

    @Override
    public void forEach(Consumer<? super T> action) {
        // no elements
    }
}
