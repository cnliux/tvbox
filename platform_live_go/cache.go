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
	c.lock.Lock()
	c.values[key] = cacheEntry{storedAt: time.Now(), stream: resolved}
	c.lock.Unlock()
	return resolved, c.ttlSeconds(), nil
}

// resolveFresh 绕过缓存重新解析并覆盖缓存条目。
func (c *streamCache) resolveFresh(site, roomID string, resolve func(string) (*ResolvedLiveStream, error)) (*ResolvedLiveStream, int64, error) {
	resolved, err := resolve(roomID)
	if err != nil {
		return nil, 0, err
	}
	c.lock.Lock()
	c.values[cacheKey(site, roomID)] = cacheEntry{storedAt: time.Now(), stream: resolved}
	c.lock.Unlock()
	return resolved, c.ttlSeconds(), nil
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
