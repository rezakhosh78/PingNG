package main

// #include <stdlib.h>
import "C"

import (
	"math"
	"net/netip"
	"strings"

	"github.com/amnezia-vpn/amneziawg-android/chainedproxy"
	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
)

func parseAddresses(raw string) ([]netip.Addr, error) {
	var addresses []netip.Addr
	for _, part := range strings.Split(raw, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		if strings.Contains(part, "/") {
			prefix, err := netip.ParsePrefix(part)
			if err != nil {
				return nil, err
			}
			addresses = append(addresses, prefix.Addr())
		} else {
			address, err := netip.ParseAddr(part)
			if err != nil {
				return nil, err
			}
			addresses = append(addresses, address)
		}
	}
	return addresses, nil
}

//export awgStartProxy
func awgStartProxy(settings, addresses, dns string, mtu, psiphonPort int32) (handle int32) {
	handle = -1
	var dev *device.Device
	var gateway *chainedproxy.Gateway
	defer func() {
		if recover() != nil {
			if gateway != nil {
				gateway.Close()
			}
			if dev != nil {
				dev.Close()
			}
			handle = -1
		}
	}()
	local, err := parseAddresses(addresses)
	if err != nil || len(local) == 0 {
		return -1
	}
	servers, err := parseAddresses(dns)
	if err != nil || len(servers) == 0 {
		return -1
	}
	tun, network, err := netstack.CreateNetTUN(local, servers, int(mtu))
	if err != nil {
		return -1
	}
	dev = device.NewDevice(tun, conn.NewStdNetBind(), device.NewLogger(device.LogLevelError, "AWG/Psiphon: "))
	if err = dev.IpcSet(settings); err != nil {
		dev.Close()
		return -1
	}
	dev.DisableSomeRoamingForBrokenMobileSemantics()
	if err = dev.Up(); err != nil {
		dev.Close()
		return -1
	}
	gateway, err = chainedproxy.Start(network.DialContext, int(psiphonPort))
	if err != nil {
		dev.Close()
		return -1
	}
	tunnelHandlesMu.Lock()
	defer tunnelHandlesMu.Unlock()
	for i := int32(0); i < math.MaxInt32; i++ {
		if _, exists := tunnelHandles[i]; !exists {
			tunnelHandles[i] = TunnelHandle{device: dev, proxy: gateway}
			return i
		}
	}
	gateway.Close()
	dev.Close()
	return -1
}

//export awgProxyPort
func awgProxyPort(handle int32, final int32) int32 {
	tunnelHandlesMu.Lock()
	defer tunnelHandlesMu.Unlock()
	tunnel, ok := tunnelHandles[handle]
	if !ok || tunnel.proxy == nil {
		return -1
	}
	if final != 0 {
		return int32(tunnel.proxy.SOCKSPort)
	}
	return int32(tunnel.proxy.HTTPPort)
}
