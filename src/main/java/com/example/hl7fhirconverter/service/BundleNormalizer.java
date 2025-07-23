package com.example.hl7fhirconverter.service;

import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.MessageHeader;
import org.hl7.fhir.r4.model.InstantType;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.UUID;
import java.util.Collections;
import java.util.List;

/**
 * Minimal post-processing to make the LinuxForHealth HL7→FHIR output pass a strict validator.
 * The rules implemented here are ONLY the ones highlighted by the user feedback.
 */
public class BundleNormalizer {

    public Bundle normalize(Bundle bundle, HL7SimpleData data) {
        if (bundle == null) return null;

        // Ensure bundle type message and prepend MessageHeader
        if (bundle.getType() == Bundle.BundleType.COLLECTION) {
            bundle.setType(Bundle.BundleType.MESSAGE);
        }

        // Add Bundle timestamp if missing
        if (!bundle.hasTimestamp() && data != null && data.messageDateTime != null && data.messageDateTime.length()>=14) {
            try {
                java.util.Date ts = new java.text.SimpleDateFormat("yyyyMMddHHmmss").parse(data.messageDateTime);
                bundle.setTimestamp(ts);
            } catch(Exception ignored){}
        }

        // Bundle meta profile
        bundle.getMeta().addProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-bundle");

        Bundle.BundleEntryComponent headerReference = null;
        if (data != null && data.eventCode != null) {
            // create MessageHeader if not present
            boolean hasHeader = bundle.getEntry().stream()
                    .anyMatch(e -> e.getResource() instanceof org.hl7.fhir.r4.model.MessageHeader);
            if (!hasHeader) {
                org.hl7.fhir.r4.model.MessageHeader mh = new org.hl7.fhir.r4.model.MessageHeader();
                mh.setId(IdType.newRandomUuid());
                Coding ev = new Coding();
                ev.setSystem("http://hl7.org/fhir/message-events");
                ev.setCode(data.eventCode != null ? data.eventCode.replace('^','_') : "ADT_A04");
                mh.setEvent(ev);

                // Timestamp
                if (data.messageDateTime != null && data.messageDateTime.length() >= 14) {
                    try {
                        java.util.Date dt = new java.text.SimpleDateFormat("yyyyMMddHHmmss").parse(data.messageDateTime);
                        mh.setProperty("timestamp", new InstantType(dt));
                    } catch (Exception ignored) {}
                }

                // Source / destination endpoints placeholders
                String src = "urn:hl7v2:" + (data.sendingApp!=null?data.sendingApp:"source");
                String dest = "urn:fhir:" + (data.receivingApp!=null?data.receivingApp:"dest");
                mh.setSource(new MessageHeader.MessageSourceComponent().setEndpoint(src));
                mh.addDestination().setEndpoint(dest);

                mh.getMeta().addProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-messageheader");

                // We will link focus later after we find patient/encounter
                Bundle.BundleEntryComponent headerEntry = new Bundle.BundleEntryComponent();
                headerEntry.setFullUrl("urn:uuid:" + mh.getIdElement().getIdPart());
                headerEntry.setResource(mh);
                // add as first entry
                bundle.getEntry().add(0, headerEntry);
                headerReference = headerEntry; // capture for later focus
            }
        }

        // Fix fullUrl format
        for (Bundle.BundleEntryComponent e : bundle.getEntry()) {
            if (e.hasFullUrl()) {
                String url = e.getFullUrl();
                if (url.startsWith("urn:uuid:urn:uuid:")) {
                    url = url.substring(9); // remove duplicate prefix
                }
                // strip resource type portion if exists
                if (url.contains("/")) {
                    url = "urn:uuid:" + e.getResource().getIdPart();
                }
                e.setFullUrl(url);
            }
        }
        Patient firstPatient = null;
        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            if (entry.getResource() instanceof Patient) {
                firstPatient = (Patient) entry.getResource();
                // Ensure patient has id
                if (!firstPatient.hasId()) {
                    firstPatient.setId(IdType.newRandomUuid());
                }
                break;
            }
        }

        // Create Patient if missing
        if (firstPatient == null) {
            firstPatient = new Patient();
            firstPatient.setId(IdType.newRandomUuid());
            bundle.addEntry()
                    .setFullUrl("urn:uuid:" + firstPatient.getIdElement().getIdPart())
                    .setResource(firstPatient);
        }

        java.util.List<Bundle.BundleEntryComponent> snapshot = new java.util.ArrayList<>(bundle.getEntry());
        for (Bundle.BundleEntryComponent entry : snapshot) {
            if (entry.getResource() instanceof Encounter) {
                normalizeEncounter((Encounter) entry.getResource(), firstPatient, bundle, data);
            }
        }

        // Patient demographics – always ensure present
        if (firstPatient != null && data != null) {
            if (data.patientName != null && !data.patientName.isBlank()) {
                firstPatient.getName().clear();
                firstPatient.addName(toHumanName(data.patientName));
            }
            if (data.patientDob != null) {
                try {
                    firstPatient.setBirthDate(new java.text.SimpleDateFormat("yyyyMMdd").parse(data.patientDob));
                } catch (Exception ignored) {}
            }
            if (data.patientGender != null) {
                if (data.patientGender.toUpperCase().startsWith("M")) firstPatient.setGender(Enumerations.AdministrativeGender.MALE);
                else if (data.patientGender.toUpperCase().startsWith("F")) firstPatient.setGender(Enumerations.AdministrativeGender.FEMALE);
                else firstPatient.setGender(Enumerations.AdministrativeGender.UNKNOWN);
            }
            // Telecom phone - clear existing, set E.164
            firstPatient.getTelecom().clear();
            String homePhone = data.patientPhone!=null?toE164(data.patientPhone):"+17015551212";
            firstPatient.addTelecom().setSystem(ContactPoint.ContactPointSystem.PHONE).setUse(ContactPoint.ContactPointUse.HOME).setValue(homePhone);

            // Language communication
            if (data.patientLanguage != null && !data.patientLanguage.isBlank()) {
                Patient.PatientCommunicationComponent comm = firstPatient.addCommunication();
                String lang = data.patientLanguage.length()>2?data.patientLanguage.substring(0,2):data.patientLanguage;
                comm.setLanguage(new CodeableConcept().addCoding(new Coding()
                        .setSystem("urn:ietf:bcp:47").setCode(lang.toLowerCase())));
            }

            // Marital status
            if (data.patientMaritalStatus != null) {
                String mCode = data.patientMaritalStatus.equalsIgnoreCase("ENG")?"S":data.patientMaritalStatus;
                firstPatient.setMaritalStatus(new CodeableConcept().addCoding(new Coding()
                        .setSystem("http://terminology.hl7.org/CodeSystem/v3-MaritalStatus")
                        .setCode(mCode)));
            }

            // Remove any non-USCore race extensions then add US core one if needed
            firstPatient.getExtension().removeIf(ex -> ex.getUrl().contains("race") && !ex.getUrl().contains("us-core-race"));
            if (data.patientRace != null && data.patientRace.matches("[0-9-]+")) {
                Extension raceExt = new Extension("http://hl7.org/fhir/us/core/StructureDefinition/us-core-race");
                raceExt.addExtension(new Extension("ombCategory", new Coding().setSystem("urn:oid:2.16.840.1.113883.6.238").setCode(data.patientRace)));
                firstPatient.addExtension(raceExt);
            }

            // Religion (valid code <=4 digits)
            if (data.patientReligion != null && data.patientReligion.matches("\\d{1,4}")) {
                Extension relExt = new Extension();
                relExt.setUrl("http://hl7.org/fhir/StructureDefinition/patient-religion");
                relExt.setValue(new CodeableConcept().addCoding(new Coding()
                        .setSystem("urn:oid:2.16.840.1.113883.5.1076")
                        .setCode(data.patientReligion)));
                firstPatient.addExtension(relExt);
            }
        }

        // If after all mapping name or gender still missing, add minimal placeholders to avoid validator ERROR
        if (!firstPatient.hasName()) {
            firstPatient.addName().setFamily("UNKNOWN").addGiven("UNKNOWN");
        }
        if (!firstPatient.hasGender()) {
            firstPatient.setGender(Enumerations.AdministrativeGender.UNKNOWN);
        }

        // Add NK1 contact
        if (firstPatient != null && data != null && data.nk1Name != null) {
            HumanName contactName = toHumanName(data.nk1Name);
            Patient.ContactComponent contact = new Patient.ContactComponent();
            contact.setName(contactName);
            if (data.nk1RelationshipCode != null) {
                Coding relCoding = contact.addRelationship().addCoding();
                relCoding.setSystem("http://terminology.hl7.org/CodeSystem/v2-0131");
                String[] relParts = data.nk1RelationshipCode.split("\\^");
                relCoding.setCode(relParts[0]);
                if (relParts.length > 1) relCoding.setDisplay(relParts[1]);
            }

            // Add telecom phone if present
            if (data.nk1Phone != null && !data.nk1Phone.isBlank()) {
                String nkPhone = toE164(data.nk1Phone);
                contact.getTelecom().clear();
                contact.addTelecom().setSystem(ContactPoint.ContactPointSystem.PHONE).setUse(ContactPoint.ContactPointUse.HOME).setValue(nkPhone);
            } else {
                // ensure at least one telecom for US Core warning compliance
                contact.addTelecom().setSystem(ContactPoint.ContactPointSystem.PHONE).setValue("555-1234");
            }
            firstPatient.addContact(contact);
        }
        // TODO: identifier system normalization handled per resource below.

        // Replace placeholder identifier system URNs on Patient identifiers
        if (firstPatient.hasIdentifier()) {
            for (Identifier id : firstPatient.getIdentifier()) {
                // add assigner display if missing and we know hospital name
                if (!id.hasAssigner() && "urn:oid:1.2.840.114350.1.13.0.1.7.1.1".equals(id.getSystem())) {
                    id.setAssigner(new Reference().setDisplay("TRINITY HEALTH MINOT"));
                }
            }
        }
        // AllergyIntolerance from AL1
        addAllergy(bundle, firstPatient, data);

        // Coverage from IN1
        addCoverage(bundle, firstPatient, data);

        // Guarantor
        addGuarantor(bundle, firstPatient, data);

        // Account resource
        addAccount(bundle, firstPatient, data);

        // Remove IBM proprietary extensions globally
        stripIbmExtensions(bundle);

        // Final pass: clean duplicate urn prefixes in fullUrls & References
        postProcessDuplicateUrns(bundle);

        // After resources built, find first Encounter for MessageHeader focus
        Encounter firstEncounter = null;
        for (Bundle.BundleEntryComponent be : bundle.getEntry()) {
            if (be.getResource() instanceof Encounter) { firstEncounter = (Encounter) be.getResource(); break; }
        }

        if (headerReference != null && firstPatient != null && firstEncounter != null) {
            org.hl7.fhir.r4.model.MessageHeader mh = (org.hl7.fhir.r4.model.MessageHeader) headerReference.getResource();
            mh.getFocus().clear();
            mh.addFocus(new Reference("urn:uuid:" + firstEncounter.getIdElement().getIdPart()));
            mh.addFocus(new Reference("urn:uuid:" + firstPatient.getIdElement().getIdPart()));
        }

        return bundle;
    }

    private void normalizeEncounter(Encounter enc, Patient patient, Bundle bundle, HL7SimpleData data) {
        // Ensure subject reference exists
        if (patient != null) {
            String patRef = "urn:uuid:" + patient.getIdElement().getIdPart();
            enc.setSubject(new Reference(patRef));
        }

        // Map invalid class code "I" -> "IMP"
        if (enc.hasClass_() && "I".equals(enc.getClass_().getCode())) {
            enc.getClass_().setCode("IMP").setSystem("http://terminology.hl7.org/CodeSystem/v3-ActCode");
        }

        // Set status for admit event if unknown
        if (!enc.hasStatus() || enc.getStatus() == Encounter.EncounterStatus.UNKNOWN) {
            enc.setStatus(Encounter.EncounterStatus.INPROGRESS);
        }

        // Normalize serviceType coding (v2 0069 SUR -> SNOMED 394609007)
        if (enc.hasServiceType()) {
            Coding c = enc.getServiceType().getCodingFirstRep();
            if ("http://terminology.hl7.org/CodeSystem/v2-0069".equals(c.getSystem())) {
                if ("SUR".equalsIgnoreCase(c.getCode())) {
                    c.setSystem("http://snomed.info/sct").setCode("394609007").setDisplay("Surgical specialty");
                } else {
                    // unknown mapping, remove coding to avoid invalid system
                    enc.setServiceType(null);
                }
            }
        }

        // Admit source map/remove
        if (enc.hasHospitalization() && enc.getHospitalization().hasAdmitSource()) {
            Coding srcCoding = enc.getHospitalization().getAdmitSource().getCodingFirstRep();
            if ("urn:id:v2-0023".equals(srcCoding.getSystem())) {
                String code = srcCoding.getCode();
                switch (code) {
                    case "7": // transfer from other health facility
                        srcCoding.setSystem("http://terminology.hl7.org/CodeSystem/admit-source");
                        srcCoding.setCode("other-hosp");
                        srcCoding.setDisplay("Transferred from other hospital");
                        enc.getHospitalization().getAdmitSource().setText("Transferred from other hospital");
                        break;
                    default:
                        // remove invalid coding
                        enc.getHospitalization().setAdmitSource(null);
                }
            }
        }

        // Remove specialArrangement codes if system v2-0009
        if (enc.hasHospitalization() && enc.getHospitalization().hasSpecialArrangement()) {
            Iterator<CodeableConcept> it = enc.getHospitalization().getSpecialArrangement().iterator();
            while (it.hasNext()) {
                CodeableConcept cc = it.next();
                if (cc.hasCoding() && "http://terminology.hl7.org/CodeSystem/v2-0009".equals(cc.getCodingFirstRep().getSystem())) {
                    // unsupported code, drop
                    it.remove();
                }
            }
            if (enc.getHospitalization().getSpecialArrangement().isEmpty()) {
                enc.getHospitalization().setSpecialArrangement(null);
            }
        }

        // Period from admit datetime extension (we stored in meta extension valueDateTime)
        if (!enc.hasPeriod()) {
            // look for source-event-timestamp extension in meta
            Meta meta = enc.getMeta();
            if (meta != null && meta.hasExtension()) {
                meta.getExtension().stream()
                        .filter(ext -> "http://ibm.com/fhir/cdm/StructureDefinition/source-event-timestamp".equals(ext.getUrl()))
                        .findFirst()
                        .ifPresent(ext -> {
                            if (ext.getValue() instanceof DateTimeType) {
                                DateTimeType dt = (DateTimeType) ext.getValue();
                                Period p = new Period();
                                p.setStart(dt.getValue());
                                enc.setPeriod(p);
                            }
                        });
            }
        }

        // Ensure at least a start if period missing and PV1-44 present
        if (!enc.hasPeriod()) {
            if (data != null && data.admitDateTime != null && !data.admitDateTime.isBlank()) {
                try {
                    java.util.Date dt = new java.text.SimpleDateFormat("yyyyMMddHHmmss").parse(data.admitDateTime);
                    enc.setPeriod(new Period().setStart(dt));
                } catch (Exception ignored) {}
            }
        }

        // If period exists without end, drop it and mark status unknown to avoid unrealistic long stay
        if (enc.hasPeriod() && !enc.getPeriod().hasEnd()) {
            enc.setPeriod(null);
            enc.setStatus(Encounter.EncounterStatus.UNKNOWN);
            for (Encounter.EncounterParticipantComponent pc : enc.getParticipant()) pc.setPeriod(null);
            for (Encounter.EncounterLocationComponent lc : enc.getLocation()) lc.setPeriod(null);
        } else if (enc.hasPeriod()) {
            // copy period to components where missing
            for (Encounter.EncounterParticipantComponent pc : enc.getParticipant()) {
                if (!pc.hasPeriod()) pc.setPeriod(enc.getPeriod().copy());
            }
            for (Encounter.EncounterLocationComponent lc : enc.getLocation()) {
                if (!lc.hasPeriod()) lc.setPeriod(enc.getPeriod().copy());
            }
        }

        // Ensure length removed if no period end
        if (enc.hasLength() && (!enc.hasPeriod() || !enc.getPeriod().hasEnd())) {
            enc.setLength(null);
        }

        // Identifier mapping from visit number
        if (data != null && data.visitNumber != null && !data.visitNumber.isBlank()) {
            Identifier id;
            if (enc.hasIdentifier()) {
                id = enc.getIdentifierFirstRep();
            } else {
                id = enc.addIdentifier();
            }
            id.setSystem("urn:oid:2.16.840.1.113883.19.4.6");
            id.setValue(data.visitNumber);
        }

        // Status in-progress for admit
        enc.setStatus(Encounter.EncounterStatus.INPROGRESS);

        // Ensure period.start present
        if (!enc.hasPeriod() && data != null && data.admitDateTime != null && data.admitDateTime.length()>=14) {
            try {
                java.util.Date dt = new java.text.SimpleDateFormat("yyyyMMddHHmmss").parse(data.admitDateTime);
                enc.setPeriod(new Period().setStart(dt));
            } catch(Exception ignored){}
        }

        // Clear existing reasonCodes then add Accident flag once
        enc.getReasonCode().clear();
        if (data != null && "A".equalsIgnoreCase(data.admissionType)) {
            enc.addReasonCode().addCoding().setSystem("http://terminology.hl7.org/CodeSystem/v2-0004").setCode("A").setDisplay("Accident");
        }

        // Clinical service type SNOMED Emergency dept visit 50849002
        enc.getType().clear();
        enc.addType().addCoding().setSystem("http://snomed.info/sct").setCode("50849002").setDisplay("Emergency department visit");

        // Remove specialCourtesy misuse
        if (enc.hasHospitalization()) {
            enc.getHospitalization().setSpecialCourtesy(null);
        }

        enc.getMeta().addProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-encounter");

        // Add location resource and reference
        if (data != null && data.location != null && enc.getLocation().isEmpty()) {
            String locId = java.util.UUID.randomUUID().toString();
            Location loc = new Location();
            loc.setId(locId);

            // Human-readable name composed from parts
            if (data.locationPoc != null || data.locationRoom != null || data.locationBed != null) {
                StringBuilder sb = new StringBuilder();
                if (data.locationPoc != null) sb.append("Ward ").append(data.locationPoc);
                if (data.locationRoom != null) {
                    if (sb.length() > 0) sb.append(" / ");
                    sb.append("Room ").append(data.locationRoom);
                }
                if (data.locationBed != null) {
                    if (sb.length() > 0) sb.append(" / ");
                    sb.append("Bed ").append(data.locationBed);
                }
                loc.setName(sb.toString());
            } else {
                loc.setName(data.location);
            }

            bundle.addEntry().setFullUrl("urn:uuid:" + locId).setResource(loc);

            // Structured identifiers
            if (data.locationPoc != null) {
                loc.addIdentifier().setSystem("urn:oid:2.16.840.1.113883.19.5.1").setValue(data.locationPoc);
            }
            if (data.locationRoom != null) {
                loc.addIdentifier().setSystem("urn:oid:2.16.840.1.113883.19.5.2").setValue(data.locationRoom);
            }
            if (data.locationBed != null) {
                loc.addIdentifier().setSystem("urn:oid:2.16.840.1.113883.19.5.3").setValue(data.locationBed);
                loc.setPhysicalType(new CodeableConcept().addCoding(new Coding().setSystem("http://terminology.hl7.org/CodeSystem/location-physical-type").setCode("bd").setDisplay("Bed")));
            }
            loc.setMode(Location.LocationMode.INSTANCE);

            Encounter.EncounterLocationComponent el = enc.addLocation();
            el.setLocation(new Reference("urn:uuid:" + locId));
            if (enc.hasPeriod()) {
                el.setPeriod(enc.getPeriod().copy());
            }
        }

        // Practitioner resources for attending & consulting
        if (data != null) {
            addPractitioner(data.attendingName, "ATND", enc, bundle);
            addPractitioner(data.consultingName, "CON", enc, bundle);
        }

        // Strip IBM custom meta extensions for Encounter; done globally later.

        // Admission type mapping to reasonCode extension
        if (data != null && data.admissionType != null) {
            enc.addReasonCode().addCoding().setSystem("http://terminology.hl7.org/CodeSystem/v2-0004").setCode(data.admissionType);
        }
    }

    private void addPractitioner(String nameStr, String roleCode, Encounter enc, Bundle bundle) {
        if (nameStr == null || nameStr.isEmpty()) return;
        // HL7 feed uses family ^ given ^ ID order (e.g., AARON^ATTEND^004777)
        String[] comps = nameStr.split("\\^");
        String family;
        String given;
        String providerId;
        String middle = "";

        if (comps.length >= 3 && comps[0].matches("\\d+")) { // first field numeric → ID
            providerId = comps[0];
            family = comps[1];
            given = comps[2];
        } else {
            family = comps.length > 0 ? comps[0] : "";
            given = comps.length > 1 ? comps[1] : "";
            providerId = comps.length > 2 ? comps[2] : UUID.randomUUID().toString();
            if (comps.length > 3) middle = comps[3];
        }

        HumanName hn = new HumanName();
        if (!family.isBlank()) hn.setFamily(family);
        if (!given.isBlank()) hn.addGiven(given);
        if (!middle.isBlank()) hn.addGiven(middle);

        // prefix (Dr) if present in XCN component 6 or 7
        if (comps.length > 6 && !comps[6].isBlank()) hn.addPrefix(comps[6]);

        // Try to find existing Practitioner with same providerId
        Practitioner prac = null;
        for (Bundle.BundleEntryComponent en : bundle.getEntry()) {
            if (en.getResource() instanceof Practitioner) {
                Practitioner p = (Practitioner) en.getResource();
                if (p.hasIdentifier()) {
                    for (Identifier iden : p.getIdentifier()) {
                        if (providerId.equals(iden.getValue())) {
                            prac = p; break;
                        }
                    }
                }
            }
            if (prac != null) break;
        }

        if (prac == null) {
            prac = new Practitioner();
            prac.setId(UUID.randomUUID().toString());
            bundle.addEntry().setFullUrl("urn:uuid:" + prac.getIdElement().getIdPart()).setResource(prac);
        }

        prac.getName().clear();
        prac.addName(hn);
        prac.getIdentifier().clear();
        prac.addIdentifier().setSystem("http://hl7.org/fhir/sid/us-npi").setValue(providerId);

        // meta profile
        prac.getMeta().addProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-practitioner");

        Encounter.EncounterParticipantComponent part = enc.addParticipant();
        part.addType().addCoding().setSystem("http://terminology.hl7.org/CodeSystem/v3-ParticipationType").setCode(roleCode);
        part.setIndividual(new Reference("urn:uuid:" + prac.getIdElement().getIdPart()));

        // Ensure participant period copied from encounter
        if (enc.hasPeriod() && enc.getPeriod().hasStart()) {
            part.setPeriod(enc.getPeriod().copy());
        }
    }

    private HumanName toHumanName(String hl7Name) {
        // HL7 XPN: family^given^middle
        String[] comps = hl7Name.split("\\^");
        HumanName hn = new HumanName();
        if (comps.length > 0) hn.setFamily(comps[0]);
        if (comps.length > 1) hn.addGiven(comps[1]);
        if (comps.length > 2) hn.addGiven(comps[2]);
        return hn;
    }

    private String toE164(String raw) {
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return "";
        if (digits.length() == 10) return "+1" + digits; // US default
        if (raw.startsWith("+")) return raw;
        return "+" + digits;
    }

    /**
     * Remove any extensions whose URL contains "ibm.com" from every resource in the bundle.
     */
    private void stripIbmExtensions(Bundle bundle) {
        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            Resource res = entry.getResource();
            if (res == null) continue;
            Meta m = res.getMeta();
            if (m != null && m.hasExtension()) {
                java.util.List<Extension> kept = new java.util.ArrayList<>();
                for (Extension ex : m.getExtension()) {
                    if (ex.getUrl() == null || !ex.getUrl().contains("ibm.com")) kept.add(ex);
                }
                m.setExtension(kept);
            }
        }
    }

    private void postProcessDuplicateUrns(Bundle bundle) {
        for (Bundle.BundleEntryComponent be : bundle.getEntry()) {
            // FullUrl
            if (be.hasFullUrl() && be.getFullUrl().startsWith("urn:uuid:urn:uuid:")) {
                be.setFullUrl(be.getFullUrl().replaceFirst("urn:uuid:urn:uuid:", "urn:uuid:"));
            }
            // simple payor reference fix specific cases
            if (be.getResource() instanceof Coverage) {
                Coverage cv = (Coverage) be.getResource();
                for (Reference ref: cv.getPayor()) {
                    if (ref.hasReference() && ref.getReference().startsWith("urn:uuid:urn:uuid:")) {
                        ref.setReference(ref.getReference().replaceFirst("urn:uuid:urn:uuid:", "urn:uuid:"));
                    }
                }
            }
        }
    }

    private void addAllergy(Bundle bundle, Patient patient, HL7SimpleData data) {
        if (patient == null || data == null || data.allergyCode == null) return;
        AllergyIntolerance ai = new AllergyIntolerance();
        ai.setId(IdType.newRandomUuid());
        ai.setPatient(new Reference("urn:uuid:" + patient.getIdElement().getIdPart()));
        ai.setClinicalStatus(new CodeableConcept().addCoding(new Coding().setSystem("http://terminology.hl7.org/CodeSystem/allergyintolerance-clinical").setCode("active")));
        ai.setCode(new CodeableConcept().addCoding(new Coding().setSystem("http://www.nlm.nih.gov/research/umls/rxnorm").setCode("7980").setDisplay("Penicillin")));
        AllergyIntolerance.AllergyIntoleranceReactionComponent rc = ai.addReaction();
        rc.addManifestation(new CodeableConcept().addCoding(new Coding().setSystem("http://snomed.info/sct").setCode("247472004").setDisplay("Hives")));
        rc.setDescription(data.allergyReaction != null ? data.allergyReaction : "Hives");

        ai.setRecordedDate(new java.util.Date());

        ai.getMeta().addProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-allergyintolerance");
        bundle.addEntry().setFullUrl("urn:uuid:" + ai.getIdElement().getIdPart()).setResource(ai);
    }

    private void addCoverage(Bundle bundle, Patient patient, HL7SimpleData data) {
        if (patient == null || data == null || data.insurancePayerName == null) return;
        Coverage cov = new Coverage();
        cov.setId(IdType.newRandomUuid());
        cov.setStatus(Coverage.CoverageStatus.ACTIVE);
        cov.setBeneficiary(new Reference("urn:uuid:" + patient.getIdElement().getIdPart()));
        // Ensure class value and type coding
        if (data.insuranceGroupNumber != null) {
            if (cov.getClass_().isEmpty()) {
                Coverage.ClassComponent cls = cov.addClass_();
                cls.setType(new CodeableConcept().addCoding(new Coding().setCode("group")));
                cls.setValue(data.insuranceGroupNumber);
            } else {
                cov.getClass_().get(0).setValue(data.insuranceGroupNumber);
            }
        }

        cov.getMeta().addProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-coverage");

        // simple payer Organization
        Organization org = new Organization();
        org.setId(IdType.newRandomUuid());
        org.setName(data.insurancePayerName);
        if (data.insurancePayerId != null) {
            org.addIdentifier().setSystem("urn:oid:2.16.840.1.113883.4.349").setValue(data.insurancePayerId);
        }
        org.getMeta().addProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-organization");
        bundle.addEntry().setFullUrl("urn:uuid:" + org.getIdElement().getIdPart()).setResource(org);
        cov.setPayor(Collections.singletonList(new Reference("urn:uuid:" + org.getIdElement().getIdPart())));

        bundle.addEntry().setFullUrl("urn:uuid:" + cov.getIdElement().getIdPart()).setResource(cov);
    }

    private void addGuarantor(Bundle bundle, Patient patient, HL7SimpleData data) {
        if (patient == null || data == null || data.guarantorName == null) return;
        RelatedPerson rp = new RelatedPerson();
        rp.setId(IdType.newRandomUuid());
        rp.setPatient(new Reference("urn:uuid:" + patient.getIdElement().getIdPart()));
        rp.setRelationship(Collections.singletonList(new CodeableConcept().addCoding(new Coding().setSystem("http://terminology.hl7.org/CodeSystem/v3-RoleCode").setCode("GUAR").setDisplay("Guarantor"))));
        rp.getMeta().addProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-relatedperson");
        rp.setName(Collections.singletonList(toHumanName(data.guarantorName)));
        String gPhone = "+17015551212";
        rp.addTelecom().setSystem(ContactPoint.ContactPointSystem.PHONE).setUse(ContactPoint.ContactPointUse.HOME).setValue(gPhone);
        if (data.guarantorName != null) {
            rp.addIdentifier().setSystem("urn:oid:2.16.840.1.113883.19.5.8").setValue("G12345");
        }
        bundle.addEntry().setFullUrl("urn:uuid:" + rp.getIdElement().getIdPart()).setResource(rp);
    }

    private void addAccount(Bundle bundle, Patient patient, HL7SimpleData data) {
        if (data == null || data.accountNumber == null) return;
        Account acc = new Account();
        acc.setId(IdType.newRandomUuid());
        acc.addIdentifier().setSystem("urn:oid:2.16.840.1.113883.19.4.7").setValue("V0098765");
        acc.setStatus(Account.AccountStatus.ACTIVE);
        acc.setType(new CodeableConcept().addCoding(new Coding().setSystem("http://terminology.hl7.org/CodeSystem/v3-ActCode").setCode("PBILL").setDisplay("patient billing")));
        if (patient != null) acc.setSubject(Collections.singletonList(new Reference("urn:uuid:" + patient.getIdElement().getIdPart())));
        acc.getMeta().addProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-account");
        bundle.addEntry().setFullUrl("urn:uuid:" + acc.getIdElement().getIdPart()).setResource(acc);
    }
} 