/*
 * Copyright 2018 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty5.resolver.dns;

import io.netty5.channel.EventLoop;
import io.netty5.util.concurrent.EventExecutor;
import io.netty5.util.concurrent.Future;
import io.netty5.util.concurrent.FutureCompletionStage;
import io.netty5.util.concurrent.FutureContextListener;
import io.netty5.util.concurrent.FutureListener;
import io.netty5.util.concurrent.Promise;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Function;

import static java.util.Collections.singletonList;

/**
 * Abstract cache that automatically removes entries for a hostname once the TTL for an entry is reached.
 *
 * @param <E>
 */
abstract class Cache<E> {
    private static final AtomicReferenceFieldUpdater<Cache.Entries, FutureAndDelay> FUTURE_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(Cache.Entries.class, FutureAndDelay.class, "expirationFuture");

    private static final Future<?> CANCELLED_FUTURE = new Future<>() {
        @Override
        public boolean cancel() {
            return false;
        }

        @Override
        public boolean isSuccess() {
            return false;
        }

        @Override
        public boolean isFailed() {
            return true;
        }

        @Override
        public boolean isCancellable() {
            return false;
        }

        @Override
        public Throwable cause() {
            return new CancellationException();
        }

        @Override
        public Future<Object> addListener(FutureListener<? super Object> listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <C> Future<Object> addListener(C context, FutureContextListener<? super C, ? super Object> listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Object> sync() throws InterruptedException {
            return this;
        }

        @Override
        public Future<Object> await() throws InterruptedException {
            return this;
        }

        @Override
        public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return true;
        }

        @Override
        public boolean await(long timeoutMillis) throws InterruptedException {
            return true;
        }

        @Override
        public Object getNow() {
            return null;
        }

        @Override
        public EventExecutor executor() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isCancelled() {
            return true;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public Object get() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FutureCompletionStage<Object> asStage() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.concurrent.Future<Object> asJdkFuture() {
            throw new UnsupportedOperationException();
        }

        @Override
        public <R> Future<R> map(Function<Object, R> mapper) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <R> Future<R> flatMap(Function<Object, Future<R>> mapper) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Object> cascadeTo(Promise<? super Object> promise) {
            throw new UnsupportedOperationException();
        }
    };
    private static final FutureAndDelay CANCELLED = new FutureAndDelay(CANCELLED_FUTURE, Integer.MIN_VALUE);

    // Two years are supported by all our EventLoop implementations and so safe to use as maximum.
    // See also: https://github.com/netty/netty/commit/b47fb817991b42ec8808c7d26538f3f2464e1fa6
    static final int MAX_SUPPORTED_TTL_SECS = (int) TimeUnit.DAYS.toSeconds(365 * 2);

    private final ConcurrentMap<String, Entries> resolveCache = new ConcurrentHashMap<>();

    /**
     * Remove everything from the cache.
     */
    final void clear() {
        while (!resolveCache.isEmpty()) {
            for (Iterator<Entry<String, Entries>> i = resolveCache.entrySet().iterator(); i.hasNext();) {
                Map.Entry<String, Entries> e = i.next();
                i.remove();

                e.getValue().clearAndCancel();
            }
        }
    }

    /**
     * Clear all entries (if anything exists) for the given hostname and return {@code true} if anything was removed.
     */
    final boolean clear(String hostname) {
        Entries entries = resolveCache.remove(hostname);
        return entries != null && entries.clearAndCancel();
    }

    /**
     * Returns all caches entries for the given hostname.
     */
    final List<? extends E> get(String hostname) {
        Entries entries = resolveCache.get(hostname);
        return entries == null ? null : entries.get();
    }

    /**
     * Cache a value for the given hostname that will automatically expire once the TTL is reached.
     */
    final void cache(String hostname, E value, int ttl, EventLoop loop) {
        Entries entries = resolveCache.get(hostname);
        if (entries == null) {
            entries = new Entries(hostname);
            Entries oldEntries = resolveCache.putIfAbsent(hostname, entries);
            if (oldEntries != null) {
                entries = oldEntries;
            }
        }
        entries.add(value, ttl, loop);
    }

    /**
     * Return the number of hostnames for which we have cached something.
     */
    final int size() {
        return resolveCache.size();
    }

    /**
     * Returns {@code true} if this entry should replace all other entries that are already cached for the hostname.
     */
    protected abstract boolean shouldReplaceAll(E entry);

    /**
     * Sort the {@link List} for a {@code hostname} before caching these.
     */
    protected void sortEntries(
            @SuppressWarnings("unused") String hostname, @SuppressWarnings("unused") List<E> entries) {
        // NOOP.
    }

    /**
     * Returns {@code true} if both entries are equal.
     */
    protected abstract boolean equals(E entry, E otherEntry);

    // Directly extend AtomicReference for intrinsics and also to keep memory overhead low.
    private final class Entries extends AtomicReference<List<E>> implements Runnable {

        private final String hostname;
        // Needs to be package-private to be able to access it via the AtomicReferenceFieldUpdater
        volatile FutureAndDelay expirationFuture;

        Entries(String hostname) {
            super(Collections.emptyList());
            this.hostname = hostname;
        }

        void add(E e, int ttl, EventLoop loop) {
            if (!shouldReplaceAll(e)) {
                for (;;) {
                    List<E> entries = get();
                    if (!entries.isEmpty()) {
                        final E firstEntry = entries.get(0);
                        if (shouldReplaceAll(firstEntry)) {
                            assert entries.size() == 1;

                            if (compareAndSet(entries, singletonList(e))) {
                                scheduleCacheExpirationIfNeeded(ttl, loop);
                                return;
                            } else {
                                // Need to try again as CAS failed
                                continue;
                            }
                        }

                        // Create a new List for COW semantics
                        List<E> newEntries = new ArrayList<>(entries.size() + 1);
                        int i = 0;
                        E replacedEntry = null;
                        do {
                            E entry = entries.get(i);
                            // Only add old entry if the address is not the same as the one we try to add as well.
                            // In this case we will skip it and just add the new entry as this may have
                            // more up-to-date data and cancel the old after we were able to update the cache.
                            if (!Cache.this.equals(e, entry)) {
                                newEntries.add(entry);
                            } else {
                                replacedEntry = entry;
                                newEntries.add(e);

                                ++i;
                                for (; i < entries.size(); ++i) {
                                    newEntries.add(entries.get(i));
                                }
                                break;
                            }
                        } while (++i < entries.size());
                        if (replacedEntry == null) {
                            newEntries.add(e);
                        }
                        sortEntries(hostname, newEntries);

                        if (compareAndSet(entries, Collections.unmodifiableList(newEntries))) {
                            scheduleCacheExpirationIfNeeded(ttl, loop);
                            return;
                        }
                    } else if (compareAndSet(entries, singletonList(e))) {
                        scheduleCacheExpirationIfNeeded(ttl, loop);
                        return;
                    }
                }
            } else {
                set(singletonList(e));
                scheduleCacheExpirationIfNeeded(ttl, loop);
            }
        }

        private void scheduleCacheExpirationIfNeeded(int ttl, EventLoop loop) {
            for (;;) {
                // We currently don't calculate a new TTL when we need to retry the CAS as we don't expect this to
                // be invoked very concurrently and also we use SECONDS anyway. If this ever becomes a problem
                // we can reconsider.
                FutureAndDelay oldFuture = FUTURE_UPDATER.get(this);
                if (oldFuture == null || oldFuture.getDelay(TimeUnit.SECONDS) > ttl) {
                    Future<?> newFuture = loop.schedule(this, ttl, TimeUnit.SECONDS);
                    // It is possible that
                    // 1. task will fire in between this line, or
                    // 2. multiple timers may be set if there is concurrency
                    // (1) Shouldn't be a problem because we will fail the CAS and then the next loop will see CANCELLED
                    //     so the ttl will not be less, and we will bail out of the loop.
                    // (2) This is a trade-off to avoid concurrency resulting in contention on a synchronized block.
                    if (FUTURE_UPDATER.compareAndSet(this, oldFuture, new FutureAndDelay(newFuture, ttl))) {
                        if (oldFuture != null) {
                            oldFuture.cancel();
                        }
                        break;
                    } else {
                        // There was something else scheduled in the meantime... Cancel and try again.
                        newFuture.cancel();
                    }
                } else {
                    break;
                }
            }
        }

        boolean clearAndCancel() {
            List<E> entries = getAndSet(Collections.emptyList());
            if (entries.isEmpty()) {
                return false;
            }

            FutureAndDelay expirationFuture = FUTURE_UPDATER.getAndSet(this, CANCELLED);
            if (expirationFuture != null) {
                expirationFuture.cancel();
            }

            return true;
        }

        @Override
        public void run() {
            // We always remove all entries for a hostname once one entry expire. This is not the
            // most efficient to do but this way we can guarantee that if a DnsResolver
            // be configured to prefer one ip family over the other we will not return unexpected
            // results to the enduser if one of the A or AAAA records has different TTL settings.
            //
            // As a TTL is just a hint of the maximum time a cache is allowed to cache stuff it's
            // completely fine to remove the entry even if the TTL is not reached yet.
            //
            // See https://github.com/netty/netty/issues/7329
            resolveCache.remove(hostname, this);

            clearAndCancel();
        }
    }

    private static final class FutureAndDelay implements Delayed {
        final Future<?> future;
        final long deadlineNanos;

        private FutureAndDelay(Future<?> future, int ttl) {
            this.future = Objects.requireNonNull(future, "future");
            deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(ttl);
        }

        void cancel() {
            future.cancel();
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(deadlineNanos - System.nanoTime(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(deadlineNanos, other.getDelay(TimeUnit.NANOSECONDS));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof FutureAndDelay && compareTo((FutureAndDelay) o) == 0;
        }

        @Override
        public int hashCode() {
            int result = future.hashCode();
            result = 31 * result + (int) (deadlineNanos ^ deadlineNanos >>> 32);
            return result;
        }
    }
}
