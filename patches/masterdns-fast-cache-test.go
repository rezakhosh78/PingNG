package client

import (
	"context"
	"os"
	"path/filepath"
	"testing"
)

func TestPingNGFastCacheSkipsInitialProbesAndRestoresOnlySavedResolvers(t *testing.T) {
	c := createTestClient(t)
	c.balancer.SetConnections([]*Connection{{Key: "a|53|t.example"}, {Key: "b|53|t.example"}})
	path := filepath.Join(t.TempDir(), "cache.txt")
	if err := writeMTUCache(path, []Connection{{Key: "a|53|t.example", UploadMTUBytes: 120, DownloadMTUBytes: 350}}); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PINGNG_MASTERDNS_MTU_CACHE_IN", path)
	if err := c.RunInitialMTUTests(context.Background()); err != nil { t.Fatal(err) }
	if c.balancer.ActiveCount() != 1 || c.syncedUploadMTU != 120 || c.syncedDownloadMTU != 350 {
		t.Fatalf("cached resolver not restored: count=%d mtu=%d/%d", c.balancer.ActiveCount(), c.syncedUploadMTU, c.syncedDownloadMTU)
	}
	if err := os.WriteFile(path, []byte("pingng-mtu-v1\nunknown|53|t.example 120 350\n"), 0600); err != nil { t.Fatal(err) }
	if err := c.restoreMTUCache(path); err == nil { t.Fatal("stale resolver was accepted") }
}
