package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net"
	"time"
)

type Discovery struct {
	store *Store
	port  int
	conn  *net.UDPConn
}

func newDiscovery(store *Store, port int) *Discovery {
	return &Discovery{store: store, port: port}
}

func (d *Discovery) Start() error {
	addr := &net.UDPAddr{IP: net.IPv4zero, Port: d.port}
	conn, err := net.ListenUDP("udp4", addr)
	if err != nil {
		return fmt.Errorf("UDP 监听 %d 失败: %w", d.port, err)
	}
	d.conn = conn
	go d.loop()
	go d.broadcaster()
	log.Printf("设备发现已启动: UDP :%d", d.port)
	return nil
}

func (d *Discovery) loop() {
	buf := make([]byte, 64*1024)
	for {
		n, src, err := d.conn.ReadFromUDP(buf)
		if err != nil {
			return
		}
		d.handle(append([]byte(nil), buf[:n]...), src)
	}
}

func (d *Discovery) broadcaster() {
	// 立即广播一次，此后每 4 秒
	d.broadcast()
	t := time.NewTicker(4 * time.Second)
	defer t.Stop()
	for range t.C {
		d.broadcast()
		d.store.PruneDevices()
	}
}

func (d *Discovery) broadcast() {
	msg := Discover{
		Type: msgDiscover,
		Info: d.store.Me(),
	}
	payload, err := json.Marshal(msg)
	if err != nil {
		return
	}
	targets := []net.IP{net.IPv4bcast, net.IPv4(127, 0, 0, 1)}
	if ifaces, err := net.Interfaces(); err == nil {
		for _, iface := range ifaces {
			if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
				continue
			}
			addrs, err := iface.Addrs()
			if err != nil {
				continue
			}
			for _, a := range addrs {
				ipnet, ok := a.(*net.IPNet)
				if !ok || ipnet.IP.To4() == nil {
					continue
				}
				ip := ipnet.IP.To4()
				mask := ipnet.Mask
				if len(mask) != 4 {
					continue
				}
				b := make(net.IP, 4)
				for i := 0; i < 4; i++ {
					b[i] = ip[i] | ^mask[i]
				}
				targets = append(targets, b)
			}
		}
	}
	seen := map[string]bool{}
	for _, ip := range targets {
		key := ip.String()
		if seen[key] {
			continue
		}
		seen[key] = true
		_, _ = d.conn.WriteToUDP(payload, &net.UDPAddr{IP: ip, Port: d.port})
	}
}

func (d *Discovery) handle(b []byte, src *net.UDPAddr) {
	t, err := decodeType(b)
	if err != nil {
		return
	}
	switch t {
	case msgDiscover:
		var m Discover
		if err := json.Unmarshal(b, &m); err != nil {
			return
		}
		d.store.UpsertDevice(m.Info, src.IP.String())
		// 回复
		resp := DiscoverResponse{
			Type: msgDiscoverResponse,
			Info: d.store.Me(),
			Port: d.port,
		}
		if out, err := json.Marshal(resp); err == nil {
			_, _ = d.conn.WriteToUDP(out, src)
		}
	case msgDiscoverResponse:
		var m DiscoverResponse
		if err := json.Unmarshal(b, &m); err != nil {
			return
		}
		d.store.UpsertDevice(m.Info, src.IP.String())
	}
}
