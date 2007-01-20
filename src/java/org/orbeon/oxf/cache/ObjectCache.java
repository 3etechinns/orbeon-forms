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

import org.orbeon.oxf.resources.OXFProperties;

import java.util.Map;
import java.util.HashMap;

/**
 * Factory for ObjectCache instances.
 */
public class ObjectCache {

    private static final String CACHE_PROPERTY_NAME_PREFIX = "oxf.";
    private static final String CACHE_PROPERTY_NAME_SIZE_SUFFIX = ".size";

    private static final int DEFAULT_SIZE = 200;

    private static Cache mainObjectCache = new MemoryCacheImpl(DEFAULT_SIZE);
    private static Map namedObjectCaches;

    private ObjectCache() {}

    /**
     * Get the intance of the main object cache.
     *
     * @return instance of cache
     */
    public static Cache instance() {
        return mainObjectCache;
    }

    /**
     * Get the instance of the object cache specified.
     *
     * @param cacheName     name of the cache
     * @return              instance of cache
     */
//    public synchronized static Cache instance(String cacheName) {
//        return instance(cacheName, DEFAULT_SIZE);
//    }

    /**
     * Get the instance of the object cache specified.
     *
     * @param cacheName     name of the cache
     * @param defaultSize   default size if size is not found in properties
     * @return              instance of cache
     */
    public synchronized static Cache instance(String cacheName, int defaultSize) {

        if (namedObjectCaches == null)
            namedObjectCaches = new HashMap();
        Cache cache = (Cache) namedObjectCaches.get(cacheName);
        if (cache == null) {
            final String propertyName = CACHE_PROPERTY_NAME_PREFIX + cacheName + CACHE_PROPERTY_NAME_SIZE_SUFFIX;
            final Integer size = OXFProperties.instance().getPropertySet().getInteger(propertyName, defaultSize);
            cache = new MemoryCacheImpl(size.intValue());
            namedObjectCaches.put(cacheName, cache);
        }
        return cache;
    }
}
