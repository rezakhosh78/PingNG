module github.com/amnezia-vpn/amneziawg-android

go 1.25.0

require golang.org/x/sys v0.36.0

require (
	github.com/google/btree v1.1.3 // indirect
	golang.org/x/time v0.9.0 // indirect
	gvisor.dev/gvisor v0.0.0-20231202080848-1f7806d17489 // indirect
)

require (
	github.com/amnezia-vpn/amneziawg-go/v3 v3.1.20260828
	golang.org/x/crypto v0.42.0 // indirect
	golang.org/x/net v0.44.0
	golang.zx2c4.com/wintun v0.0.0-20230126152724-0fa3db229ce2 // indirect
)

replace github.com/amnezia-vpn/amneziawg-go/v3 => ../amneziawg-go
