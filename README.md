# HL7 v2 ➜ FHIR Converter (MVP)

Minimal Spring Boot service that exposes a single endpoint to translate HL7 v2 messages into FHIR Bundles using the [LinuxForHealth/hl7v2-fhir-converter](https://github.com/LinuxForHealth/hl7v2-fhir-converter) library.  
A dead-simple HTML page is served from `/` for manual testing.

## Quick start

```bash
# build & run
mvn spring-boot:run
# then open http://localhost:8080
```

### API

```
POST /api/convert
Content-Type: text/plain
Body: <raw HL7 v2 message>
→ 200 OK
  JSON body with the converted FHIR Bundle
```

## Packaging

```bash
mvn clean package
java -jar target/hl7-fhir-converter-0.1.0.jar
```

## Notes

* Java 17+, Maven 3.9+.
* No persistence – conversion happens in-memory and the result is streamed back immediately.
* The UI is intentionally minimal; styling can be enhanced later. 