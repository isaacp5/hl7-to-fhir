package com.example.hl7fhirconverter.api;

import io.github.linuxforhealth.hl7.HL7ToFHIRConverter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ca.uhn.fhir.context.FhirContext;
import com.example.hl7fhirconverter.service.BundleNormalizer;
import com.example.hl7fhirconverter.service.HL7SimpleData;

import org.hl7.fhir.r4.model.Bundle;

@RestController
@RequestMapping("/api")
public class ConverterController {

    private final HL7ToFHIRConverter converter = new HL7ToFHIRConverter();
    private final FhirContext fhirCtx = FhirContext.forR4();
    private final BundleNormalizer normalizer = new BundleNormalizer();

    @PostMapping(value = "/convert", consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> convert(@RequestBody String hl7Message) {
        if (hl7Message == null || hl7Message.isBlank()) {
            return ResponseEntity.badRequest().body("{\"error\":\"HL7 message is empty\"}");
        }
        String initialJson = converter.convert(hl7Message);

        // Parse, normalize, and re-encode
        Bundle bundle = (Bundle) fhirCtx.newJsonParser().parseResource(initialJson);
        HL7SimpleData data = HL7SimpleData.parse(hl7Message);
        Bundle normalized = normalizer.normalize(bundle, data);
        String out = fhirCtx.newJsonParser().encodeResourceToString(normalized);
        return ResponseEntity.ok(out);
    }
} 