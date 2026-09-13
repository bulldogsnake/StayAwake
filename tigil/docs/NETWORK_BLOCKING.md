# Network-level blocking

The app protects **one phone**. This protects **every device on a network** —
the family's phones, the kids' tablets, the PC, the smart TV. For a household
where one person is gambling, this is often the higher-leverage intervention,
and it is the part of the brief about blocking "on their network".

Generate the artifacts first:

```bash
python3 blocklist/build_blocklist.py
ls blocklist/dist/
```

## Pi-hole / AdGuard Home (recommended for a home)

A ₱2,000 Raspberry Pi Zero, or any always-on machine.

**Pi-hole** — Settings → Adlists, or self-host `pihole-adlist.txt` and add its
URL. For a one-off import:

```bash
sudo pihole -b $(grep -v '^#' dist/pihole-adlist.txt | tr '\n' ' ')
```

**AdGuard Home** — Filters → DNS blocklists → Add, pointing at
`dist/adguardhome.txt`. AdGuard Home is the better choice here because it can
also force-disable DoH/DoT on clients, closing the biggest bypass.

Then set the router's DHCP to hand out the Pi's address as the **only** DNS
server, and block outbound port 53 and 853 to anything else — otherwise a
device that hardcodes `8.8.8.8` walks straight past it.

## Router

**MikroTik** — upload and run `dist/mikrotik.rsc`. Note it is ~345,000 static
DNS entries; check your model has the RAM (an hEX or better). Trim to
`seed/ph-gambling.txt` for smaller devices.

**OpenWrt** — copy `dist/hosts.txt` to `/etc/hosts.tigil` and add
`addn-hosts=/etc/hosts.tigil` to `/etc/dnsmasq.conf`, then
`/etc/init.d/dnsmasq restart`.

**Consumer routers** — most cannot hold a list this size. Point their DNS at a
Pi-hole instead.

## ISP / office / school resolver

`dist/rpz.zone` is a BIND Response Policy Zone — the format an ISP or a school's
IT department would actually deploy. This is the format to hand to a school
division office or a barangay that wants to act at scale.

```
# named.conf
response-policy { zone "tigil.rpz"; };
zone "tigil.rpz" { type master; file "/etc/bind/rpz.zone"; };
```

## Honest caveats

- DNS-level blocking is name-based. A raw IP address still works.
- A device using DoH to a resolver you have not blocked walks past all of this.
  Blocking outbound 853 and the known DoH endpoints at the firewall is the
  countermeasure.
- Anyone can switch to mobile data. Network blocking and on-device blocking are
  complementary; neither replaces the other.
