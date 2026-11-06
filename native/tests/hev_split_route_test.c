#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "hev-config.h"

static int parse(const char *s) {
    return hev_config_init_from_str((const unsigned char *)s, strlen(s));
}
int main(void) {
    assert(parse("socks5:\n  address: 127.0.0.1\n  port: 44715\n  udp-port: 34533\n  udp: udp\n  udp-dns-only: true\n") == 0);
    HevConfigServer *s = hev_config_get_socks5_server();
    assert(s->port == 44715 && s->udp_port == 34533 && s->udp_dns_only);
    /* Normal profiles retain one proxy and reset the chained profile policy. */
    assert(parse("socks5:\n  address: 127.0.0.1\n  port: 10808\n") == 0);
    assert(s->port == 10808 && s->udp_port == 10808 && !s->udp_dns_only);
    const char *bad[] = {"0", "65536", "-1", "34533junk"};
    for (unsigned i = 0; i < sizeof(bad)/sizeof(*bad); i++) {
        char config[256];
        snprintf(config, sizeof(config), "socks5:\n  address: 127.0.0.1\n  port: 44715\n  udp-port: '%s'\n", bad[i]);
        assert(parse(config) < 0);
    }
    puts("HEV split route configuration: PASS");
}
