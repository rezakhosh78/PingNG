# OpenVPN Android engine notices

`app/libs/openvpn3-api.jar` and `app/libs/<abi>/libovpn3.so` are from the
OpenVPN 3/OpenSSL Android variant listed in `README.md`.

| Component | Version or source | License notice |
| --- | --- | --- |
| OpenVPN 3 core and generated SWIG API | `schwabe/openvpn3` commit `3e3cd518eadf0b8d9de441f095b841f93dd09fcc` | MPL-2.0; the upstream source also offers AGPL-3.0-only. See `licenses/MPL-2.0.txt` and `licenses/AGPL-3.0-only.txt`. |
| OpenSSL | 3.4.1 | Apache-2.0. See `licenses/Apache-2.0.txt`. |
| Asio | pinned by the Android source build | Boost Software License 1.0. See `licenses/Boost-1.0.txt`. |
| LZ4 | pinned by the Android source build | BSD 2-Clause. See `licenses/LZ4-BSD-2-Clause.txt`. |

The OpenVPN 3 core source snapshot is included in `core/`. The native shared libraries are
the OpenVPN 3 client core linked with the listed dependencies; no ics-openvpn Android UI or
`de.blinkt.*` Java wrapper classes are included.
