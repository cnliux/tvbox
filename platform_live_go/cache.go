package platformlive

import (
	"sync"
	"time"
)

// streamCache 对应 server.StreamCache：解析成功直链的 TTL 缓存。
type streamCache struct {
	ttl    time.Duration
	values map[string]cacheEntry
	lock   sync.Mutex
}

type cacheEntry struct {
	storedAt time.Time
	stream   *ResolvedLiveStream
}

func newStreamCache(cacheSeconds int64) *streamCache {
	return &streamCache{
		ttl:    max(time.Duration(0), time.Duration(cacheSeconds)*time.Second),
		values: map[string]cacheEntry{},
	}
}

// cacheMaxEntries 缓存容量上限：长时间运行解析大量不同房间时防止无界增长；
// 超限先清过期条目，仍超限则淘汰最旧条目。
const cacheMaxEntries = 256

func cacheKey(site, roomID string) string { return lowerString(site) + "/" + roomID }

// getOrResolve 命中缓存则返回剩余有效期秒数，否则解析并写缓存。
func (c *streamCache) getOrResolve(site, roomID string, resolve func(string) (*ResolvedLiveStream, error)) (*ResolvedLiveStream, int64, error) {
	key := cacheKey(site, roomID)
	if c.ttl > 0 {
		c.lock.Lock()
		cached, ok := c.values[key]
		c.lock.Unlock()
		if ok {
			remainingTime := time.Until(cached.storedAt.Add(c.ttl))
			if remainingTime > 0 {
				return cached.stream, int64(remainingTime / time.Second), nil
			}
		}
	}
	resolved, err := resolve(roomID)
	if err != nil {
		return nil, 0, err
	}
	if c.ttl > 0 {
		// ttl<=0 时条目永远不会被读出，写入只会让缓存无界增长，直接跳过。
		c.lock.Lock()
		c.evictIfNeededLocked()
		c.values[key] = cacheEntry{storedAt: time.Now(), stream: resolved}
		c.lock.Unlock()
	}
	return resolved, c.ttlSeconds(), nil
}

// resolveFresh 绕过缓存重新解析并覆盖缓存条目。
func (c *streamCache) resolveFresh(site, roomID string, resolve func(string) (*ResolvedLiveStream, error)) (*ResolvedLiveStream, int64, error) {
	resolved, err := resolve(roomID)
	if err != nil {
		return nil, 0, err
	}
	if c.ttl > 0 {
		c.lock.Lock()
		c.evictIfNeededLocked()
		c.values[cacheKey(site, roomID)] = cacheEntry{storedAt: time.Now(), stream: resolved}
		c.lock.Unlock()
	}
	return resolved, c.ttlSeconds(), nil
}

// evictIfNeededLocked 在持有 c.lock 时执行：先清过期条目，仍超限则按写入时间淘汰最旧。
func (c *streamCache) evictIfNeededLocked() {
	if len(c.values) < cacheMaxEntries {
		return
	}
	now := time.Now()
	for key, entry := range c.values {
		if now.Sub(entry.storedAt) >= c.ttl {
			delete(c.values, key)
		}
	}
	for len(c.values) >= cacheMaxEntries {
		var oldestKey string
		var oldestAt time.Time
		first := true
		for key, entry := range c.values {
			if first || entry.storedAt.Before(oldestAt) {
				oldestKey = key
				oldestAt = entry.storedAt
				first = false
			}
		}
		delete(c.values, oldestKey)
	}
}

func (c *streamCache) ttlSeconds() int64 {
	return int64(c.ttl / time.Second)
}

func lowerString(value string) string {
	data := []byte(value)
	for i := range data {
		c := data[i]
		if 'A' <= c && c <= 'Z' {
			data[i] = c + ('a' - 'A')
		}
	}
	return string(data)
}
