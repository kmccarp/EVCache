package com.netflix.evcache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

import com.netflix.archaius.api.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.CacheStats;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.netflix.evcache.metrics.EVCacheMetricsFactory;
import com.netflix.evcache.util.EVCacheConfig;
import com.netflix.spectator.api.BasicTag;
import com.netflix.spectator.api.Counter;
import com.netflix.spectator.api.Gauge;
import com.netflix.spectator.api.Id;
import com.netflix.spectator.api.Tag;

import net.spy.memcached.transcoders.Transcoder;

/**
 * An In Memory cache that can be used to hold data for short duration. This is
 * helpful when the same key is repeatedly requested from EVCache within a short
 * duration. This can be turned on dynamically and can relive pressure on
 * EVCache Server instances.
 */
public class EVCacheInMemoryCache<T> {

    private static final Logger log = LoggerFactory.getLogger(EVCacheInMemoryCache.class);
    private final Property<Integer> cacheDuration; // The key will be cached for this long
  private final Property<Integer> refreshDuration;
  private final Property<Integer> exireAfterAccessDuration;
    private final Property<Integer> cacheSize; // This many items will be cached
    private final Property<Integer> poolSize; // This many threads will be initialized to fetch data from evcache async
    private final String appName;
    private final Map<String, Counter> counterMap = new ConcurrentHashMap<>();
    private final Map<String, Gauge> gaugeMap = new ConcurrentHashMap<>();

    private LoadingCache<EVCacheKey, Optional<T>> cache;
    private ExecutorService pool;

    private final Transcoder<T> tc;
    private final EVCacheImpl impl;
    private final Id sizeId;

    public EVCacheInMemoryCache(String appName, Transcoder<T> tc, EVCacheImpl impl) {
        this.appName = appName;
        this.tc = tc;
        this.impl = impl;

        this.cacheDuration = EVCacheConfig.getInstance().getPropertyRepository().get(appName + ".inmemory.expire.after.write.duration.ms", Integer.class).orElseGet(appName + ".inmemory.cache.duration.ms").orElse(0);
        this.cacheDuration.subscribe(i -> setupCache());
        this.exireAfterAccessDuration = EVCacheConfig.getInstance().getPropertyRepository().get(appName + ".inmemory.expire.after.access.duration.ms", Integer.class).orElse(0);
        this.exireAfterAccessDuration.subscribe(i -> setupCache());this.refreshDuration = EVCacheConfig.getInstance().getPropertyRepository().get(appName + ".inmemory.refresh.after.write.duration.ms", Integer.class).orElse(0);
        this.refreshDuration.subscribe(i -> setupCache());

        this.cacheSize = EVCacheConfig.getInstance().getPropertyRepository().get(appName + ".inmemory.cache.size", Integer.class).orElse(100);
        this.cacheSize.subscribe(i -> setupCache());

        this.poolSize = EVCacheConfig.getInstance().getPropertyRepository().get(appName + ".thread.pool.size", Integer.class).orElse(5);
        this.poolSize.subscribe(i -> initRefreshPool());

        final List<Tag> tags = new ArrayList<>(3);
        tags.addAll(impl.getTags());
        tags.add(new BasicTag(EVCacheMetricsFactory.METRIC, "size"));

        this.sizeId = EVCacheMetricsFactory.getInstance().getId(EVCacheMetricsFactory.IN_MEMORY, tags);
        setupCache();
        setupMonitoring(appName);
    }

  private final WriteLock writeLock = new ReentrantReadWriteLock().writeLock();
    private void initRefreshPool() {
        final ExecutorService oldPool = pool;
        writeLock.lock();
        try {
            final ThreadFactory factory = new ThreadFactoryBuilder().setDaemon(true).setNameFormat(
                    "EVCacheInMemoryCache-%d").build();
            pool = Executors.newFixedThreadPool(poolSize.get(), factory);
          if (oldPool != null) {
            oldPool.shutdown();
          }
        } finally {
            writeLock.unlock();
        }
    }


    private void setupCache() {
        try {
            CacheBuilder<Object, Object> builder = CacheBuilder.newBuilder().recordStats();
            if(cacheSize.get() > 0) {
                builder = builder.maximumSize(cacheSize.get());
            }
            if(exireAfterAccessDuration.get() > 0) {
                builder = builder.expireAfterAccess(exireAfterAccessDuration.get(), TimeUnit.MILLISECONDS);
            } else if(cacheDuration.get().intValue() > 0) {
                builder = builder.expireAfterWrite(cacheDuration.get(), TimeUnit.MILLISECONDS);
            }

            if(refreshDuration.get() > 0) {
                builder = builder.refreshAfterWrite(refreshDuration.get(), TimeUnit.MILLISECONDS);
            }
            initRefreshPool();
            final LoadingCache<EVCacheKey, Optional<T>> newCache = builder.build(
                    new CacheLoader<EVCacheKey, Optional<T>>() {
                        public Optional<T> load(EVCacheKey key) throws  EVCacheException, DataNotFoundException {
                            try {
                                return  Optional.fromNullable(impl.doGet(key, tc));
                            } catch (EVCacheException e) {
                                log.error("EVCacheException while loading key -> "+ key, e);
                                throw e;
                            } catch (Exception e) {
                                log.error("EVCacheException while loading key -> "+ key, e);
                                throw new EVCacheException("key : " + key + " could not be loaded", e);
                            }
                        }

                        @Override
                        public ListenableFuture<Optional<T>> reload(EVCacheKey key, Optional<T> oldValue) {
                            ListenableFutureTask<Optional<T>> task = ListenableFutureTask.create(new Callable<Optional<T>>() {
                                public Optional<T> call() {
                                    try {
                                        final Optional<T> t = load(key);
                                        if(t == null) {
                                            EVCacheMetricsFactory.getInstance().increment("EVCacheInMemoryCache" + "-" + appName + "-Reload-NotFound");
                                            return oldValue;
                                        } else {
                                            EVCacheMetricsFactory.getInstance().increment("EVCacheInMemoryCache" + "-" + appName + "-Reload-Success");
                                        }
                                        return t;
                                    } catch (EVCacheException e) {
                                        log.error("EVCacheException while reloading key -> "+ key, e);
                                        EVCacheMetricsFactory.getInstance().increment("EVCacheInMemoryCache" + "-" + appName + "-Reload-Fail");
                                        return oldValue;
                                    }
                                }
                            });
                            pool.execute(task);
                            return task;
                        }
                    });
          if (cache != null) {
            newCache.putAll(cache.asMap());
          }
            final Cache<EVCacheKey, Optional<T>> currentCache = this.cache;
            this.cache = newCache;
            if(currentCache != null) {
                currentCache.invalidateAll();
                currentCache.cleanUp();
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    private CacheStats previousStats;
    private long getSize() {
        final long size = cache.size();
        final CacheStats stats = cache.stats();
        if(previousStats != null) {
            try {
                getCounter("hits").increment(stats.hitCount() - previousStats.hitCount());
                getCounter("miss").increment(stats.missCount()  - previousStats.missCount());
                getCounter("evictions").increment(stats.evictionCount()  - previousStats.evictionCount());
                getCounter("requests").increment(stats.requestCount()  - previousStats.requestCount());
        
                getCounter("loadExceptionCount").increment(stats.loadExceptionCount()  - previousStats.loadExceptionCount());
                getCounter("loadCount").increment(stats.loadCount()  - previousStats.loadCount());
                getCounter("loadSuccessCount").increment(stats.loadSuccessCount()  - previousStats.loadSuccessCount());
                getCounter("totalLoadTime-ms").increment(( stats.totalLoadTime() - previousStats.totalLoadTime())/1000000);
        
                getGauge("hitrate").set(stats.hitRate());
                getGauge("loadExceptionRate").set(stats.loadExceptionRate());
                getGauge("averageLoadTime-ms").set(stats.averageLoadPenalty()/1000000);
            } catch(Exception e) {
                log.error("Error while reporting stats", e);
            }
        }
        previousStats = stats;
        return size;
    }

    @SuppressWarnings("deprecation")
    private void setupMonitoring(final String appName) {
        EVCacheMetricsFactory.getInstance().getRegistry().gauge(sizeId, this, EVCacheInMemoryCache::getSize);
    }

    private Counter getCounter(String name) {
        Counter counter = counterMap.get(name);
      if (counter != null) {
        return counter;
      }

        final List<Tag> tags = new ArrayList<>(3);
        tags.addAll(impl.getTags());
        tags.add(new BasicTag(EVCacheMetricsFactory.METRIC, name));
        counter = EVCacheMetricsFactory.getInstance().getCounter(EVCacheMetricsFactory.IN_MEMORY, tags);
        counterMap.put(name, counter);
        return counter;
    }

    private Gauge getGauge(String name) {
        Gauge gauge = gaugeMap.get(name);
      if (gauge != null) {
        return gauge;
      }

        final List<Tag> tags = new ArrayList<>(3);
        tags.addAll(impl.getTags());
        tags.add(new BasicTag(EVCacheMetricsFactory.METRIC, name));

        final Id id = EVCacheMetricsFactory.getInstance().getId(EVCacheMetricsFactory.IN_MEMORY, tags);
        gauge = EVCacheMetricsFactory.getInstance().getRegistry().gauge(id);
        gaugeMap.put(name, gauge);
        return gauge;
    }

    public T get(EVCacheKey key) throws ExecutionException {
      if (cache == null) {
        return null;
      }
        final Optional<T> val = cache.get(key);
      if (!val.isPresent()) {
        return null;
      }
      if (log.isDebugEnabled()) {
        log.debug("GET : appName : " + appName + "; Key : " + key + "; val : " + val);
      }
        return val.get();
    }

    public void put(EVCacheKey key, T value) {
      if (cache == null) {
        return;
      }
        cache.put(key, Optional.fromNullable(value));
      if (log.isDebugEnabled()) {
        log.debug("PUT : appName : " + appName + "; Key : " + key + "; val : " + value);
      }
    }

    public void delete(String key) {
      if (cache == null) {
        return;
      }
        cache.invalidate(key);
      if (log.isDebugEnabled()) {
        log.debug("DEL : appName : " + appName + "; Key : " + key);
      }
    }

    public Map<EVCacheKey, Optional<T>> getAll() {
      if (cache == null) {
        return Collections.emptyMap();
      }
        return cache.asMap();
    }

    public static final class DataNotFoundException extends EVCacheException {
        private static final long serialVersionUID = 1800185311509130263L;

        public DataNotFoundException(String message) {
            super(message);
        }
    }
}
