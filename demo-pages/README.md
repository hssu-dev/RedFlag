# demo-pages (LOCAL ONLY — never deploy publicly)

Static pages served by the `demo-pages` nginx service in `backend/docker-compose.yml`
(http://localhost:8081, emulator: http://10.0.2.2:8081, containers: http://demo-pages).
Fake domain → path mapping and demo signal overrides: `contracts/demo_hosts.json`.
Forms never submit (`onsubmit="return false"`); all form actions point at fake domains.

Stand-alone preview: `docker run --rm -p 8081:80 -v "$PWD/demo-pages:/usr/share/nginx/html:ro" nginx:stable-alpine`

## Pages not in the public repo

`dbs/`, `m365/`, `shop/`, `parcel/`, `cj/` imitate real brands (bank, Microsoft 365, sportswear outlet,
postal redelivery, Korean courier) with login / card forms. They are gitignored and kept only on the
maintainer's machine. Their fake domains stay in `contracts/demo_hosts.json`, so tests are unaffected;
opening those demo links without the pages gives a 404 from the local nginx.
The other pages (bakery, pilates, gift, giftevent, usedtrade, remote, redelivery) use fictional brands.
