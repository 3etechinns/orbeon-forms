/**
 *  Copyright (C) 2004 Orbeon, Inc.
 *
 *  This program is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version
 *  2.1 of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.
 *
 *  The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.cache;

import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.apache.commons.collections.iterators.TransformIterator;
import org.apache.commons.collections.Transformer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Very simple cache implementation.
 *
 * @noinspection SimplifiableIfStatement
 */
public class MemoryCacheImpl implements Cache {

    private final String cacheName;
    private int maxSize;

    private final String statisticsContextKey;

    private Map keyToEntryMap = new HashMap();
    private CacheLinkedList linkedList = new CacheLinkedList();
    private int currentSize;

    public MemoryCacheImpl(String cacheName, int maxSize) {
        this.cacheName = cacheName;
        this.maxSize = maxSize;

        this.statisticsContextKey =  "memory-cache-statistics." + cacheName;
    }

    public String getCacheName() {
        return cacheName;
    }

    private class MemoryCacheStatistics implements CacheStatistics {

        private int hitsCount;
        private int missCount;
        private int addCount;
        private int expirationCount;

        public int getMaxSize() { return maxSize; }
        public int getCurrentSize() { return currentSize; }

        public int getHitCount() { return hitsCount; }
        public int getMissCount() { return missCount; }
        public int getAddCount() { return addCount; }
        public int getExpirationCount() { return expirationCount; }

        public void incrementHitsCount() { hitsCount++; }
        public void incrementMissCount() { missCount++; }
        public void incrementAddCount() { addCount++; }
        public void incrementExpirationCount() { expirationCount++; }
    };

    public synchronized void add(PipelineContext pipelineContext, CacheKey key, Object validity, Object object) {
        if (key == null || validity == null || maxSize == 0) return;
        final MemoryCacheStatistics statistics = (pipelineContext != null) ? (MemoryCacheStatistics) getStatistics(pipelineContext) : null;
        if (statistics != null)
            statistics.incrementAddCount();
        CacheEntry entry = (CacheEntry) keyToEntryMap.get(key);
        if (entry == null) {
            // No existing entry found
            if (currentSize == maxSize) {
                entry = (CacheEntry) linkedList.getLast();
                keyToEntryMap.remove(entry.key);
                linkedList.removeLast();
                if (statistics != null)
                    statistics.incrementExpirationCount();
            } else {
                currentSize++;
                entry = new CacheEntry();
            }
            entry.key = key;
            entry.validity = validity;
            entry.object = object;
            keyToEntryMap.put(key, entry);
            entry.listEntry = linkedList.addFirst(entry);
        } else {
            // Update validity and move to the front
            entry.validity = validity;
            entry.object = object;
            linkedList.remove(entry.listEntry);
            entry.listEntry = linkedList.addFirst(entry);
        }
    }

    public synchronized void remove(PipelineContext context, CacheKey key) {
        final CacheEntry entry = (CacheEntry) keyToEntryMap.get(key);
        if (entry != null) {
            keyToEntryMap.remove(key);
            linkedList.remove(entry.listEntry);
            currentSize--;
        }
    }

    public synchronized int removeAll(PipelineContext pipelineContext) {
        final int previousSize = currentSize;
        keyToEntryMap = new HashMap();
        linkedList = new CacheLinkedList();
        currentSize = 0;
        return previousSize;
    }

    public synchronized Object findValid(PipelineContext pipelineContext, CacheKey key, Object validity) {

        CacheEntry entry = (CacheEntry) keyToEntryMap.get(key);
        if (entry != null && lowerOrEqual(validity, entry.validity)) {
            // Place in first position and return
            if (pipelineContext != null)
                ((MemoryCacheStatistics) getStatistics(pipelineContext)).incrementHitsCount();
            if (linkedList.getFirst() != entry) {
                linkedList.remove(entry.listEntry);
                entry.listEntry = linkedList.addFirst(entry);
            }
            return entry.object;
        } else {
            // Not latest validity
            if (pipelineContext != null)
                ((MemoryCacheStatistics) getStatistics(pipelineContext)).incrementMissCount();
            return null;
        }
    }

    public synchronized Object findValidWithExpiration(PipelineContext pipelineContext, CacheKey key, long expiration) {

        Object result = null;
        CacheEntry entry = (CacheEntry) keyToEntryMap.get(key);
        if (entry != null && entry.validity instanceof Long) {
            if (expiration == EXPIRATION_NO_EXPIRATION) {
                // Cache hit whatever the last modified date was
                result = entry.object;
            } else if (expiration != EXPIRATION_NO_CACHE) {
                // Get last modified date
                long lastModified = ((Long) entry.validity).longValue();
                if (System.currentTimeMillis() < lastModified + expiration)
                    result = entry.object;
            }
        }

        if (result != null) {
            // Place in first position and return
            if (pipelineContext != null)
                ((MemoryCacheStatistics) getStatistics(pipelineContext)).incrementHitsCount();
            if (linkedList.getFirst() != entry) {
                linkedList.remove(entry.listEntry);
                entry.listEntry = linkedList.addFirst(entry);
            }
        } else {
            // Cache miss
            if (pipelineContext != null)
                ((MemoryCacheStatistics) getStatistics(pipelineContext)).incrementMissCount();
        }
        return result;
    }

    public synchronized void setMaxSize(PipelineContext context, int maxSize) {
        if (maxSize != this.maxSize) {
            // Decrease size if necessary
            while(currentSize > maxSize)
                remove(context, ((CacheEntry) linkedList.getLast()).key);
            this.maxSize = maxSize;
        }
    }

    public Iterator iterateCacheKeys(PipelineContext context) {
        return keyToEntryMap.keySet().iterator();
    }

    public Iterator iterateCacheObjects(PipelineContext context) {
        return new TransformIterator(keyToEntryMap.keySet().iterator(), new Transformer() {
            public Object transform(Object o) {
                return ((CacheEntry) keyToEntryMap.get(o)).object;
            }
        });
    }

    public synchronized CacheStatistics getStatistics(PipelineContext pipelineContext) {
        MemoryCacheStatistics statistics = (MemoryCacheStatistics) pipelineContext.getAttribute(statisticsContextKey);
        if (statistics == null) {
            statistics = new MemoryCacheStatistics();
            pipelineContext.setAttribute(statisticsContextKey, statistics);
        }

        return statistics;
    }

    private boolean lowerOrEqual(Object left, Object right) {
        if (left instanceof List && right instanceof List) {
            List leftList = (List) left;
            List rightList = (List) right;
            if (leftList.size() != rightList.size())
                return false;
            for (Iterator leftIterator = leftList.iterator(), rightIterator = rightList.iterator();leftIterator.hasNext();) {
                Object leftObject = leftIterator.next();
                Object rightObject = rightIterator.next();
                if (!lowerOrEqual(leftObject, rightObject))
                    return false;
            }
            return true;
        } else if (left instanceof Long && right instanceof Long) {
            return ((Long) left).longValue() <= ((Long) right).longValue();
        } else {
            return false;
        }
    }
}
