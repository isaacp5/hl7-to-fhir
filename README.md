# HL7 v2 ➜ FHIR Converter

Spring&nbsp;Boot microservice that converts inbound **HL7 v2** messages (e.g. ADT, ORU, ORM) into **FHIR R4 Bundles** and post-processes them so they pass strict validation (US Core, IPS, etc.).

Typical use-cases:
* Hospital interface engines that need FHIR output instead of HL7 v2.
* Analytics / data-warehouse pipelines that standardise clinical data on FHIR.
* Proof-of-concept projects exploring LinuxForHealth’s `hl7v2-fhir-converter`.

The service exposes a single REST endpoint and (optionally) serves a React + TypeScript UI for manual testing.

---
## Features

* **Stateless conversion** – sends HL7 string, receives FHIR JSON.
* **US Core normalisation** – extra step fixes identifiers, codes, extensions, etc.
* **Swagger / OpenAPI** docs automatically generated.
* **Docker-ready** fat-jar → small alpine image (<150 MB).

---
## Quick Start (local)

```bash
# 1. clone & enter the repo
$ git clone https://github.com/<you>/hl7-v2-fhir-converter.git
$ cd hl7-v2-fhir-converter

# 2. run directly with Maven (hot-reload)
$ ./mvnw spring-boot:run
# ⇒ http://localhost:8080
```

Call the API:
```bash
curl -X POST http://localhost:8080/api/convert \
     -H "Content-Type: text/plain" \
     --data @sample.hl7
```
You’ll get a FHIR Bundle JSON back.

---
## Building a Stand-Alone Jar

```bash
./mvnw clean package
java -jar target/hl7-fhir-converter-*.jar
```

---
## Running with Docker

```bash
# build (multi-stage dockerfile already present)
docker build -t hl7-converter .
# run
docker run -p 8080:8080 hl7-converter
```

---
## REST API

```http
POST /api/convert
Content-Type: text/plain
Body: <raw HL7 v2 message>
---
200 OK
Content-Type: application/fhir+json
Body: FHIR Bundle
```

OpenAPI JSON: `GET /v3/api-docs`  │  Swagger UI: `GET /swagger-ui.html`

---
## Optional React + TypeScript Front-End

A minimal SPA lives in `ui/` (not committed by default). Generate one with:
```bash
npx create-react-app ui --template typescript
```
Point the proxy at `http://localhost:8080` and call `/api/convert` as shown in `src/api.ts`.
When you run `npm run build`, copy the static files to `src/main/resources/static/` so Spring serves them alongside the API.

---
## Requirements

* Java 17+
* Maven 3.8+

---
## License
MIT 