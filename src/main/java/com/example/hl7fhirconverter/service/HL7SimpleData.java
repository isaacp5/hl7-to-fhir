package com.example.hl7fhirconverter.service;

import java.util.Optional;

public class HL7SimpleData {
    public String location;
    public String attendingName;
    public String consultingName;
    public String nk1Name;
    public String nk1RelationshipCode;
    public String nk1RelationshipDisplay;

    // Phone number extracted from NK1-5
    public String nk1Phone;

    // MSH fields
    public String messageDateTime; // YYYYMMDDHHMMSS
    public String eventCode; // e.g., ADT^A04
    public String sendingApp;
    public String sendingFacility;
    public String receivingApp;
    public String receivingFacility;

    // Admission date/time from PV1-44 (format YYYYMMDDHHMMSS or similar)
    public String admitDateTime;

    public String patientDob;
    public String patientGender;
    public String patientName;
    public String locationPoc; // Point of care
    public String locationRoom;
    public String locationBed;

    public String patientPhone;
    public String patientLanguage;
    public String patientMaritalStatus;
    public String patientReligion;
    public String patientRace;

    // Encounter
    public String visitNumber;
    public String admissionType;

    // Allergy
    public String allergyCode;
    public String allergyReaction;

    // Insurance IN1
    public String insurancePayerId;
    public String insurancePayerName;
    public String insuranceGroupNumber;

    // Guarantor GT1
    public String guarantorName;
    public String guarantorPhone;

    // Account
    public String accountNumber;

    public static HL7SimpleData parse(String hl7) {
        HL7SimpleData d = new HL7SimpleData();
        if (hl7 == null) return d;
        // HL7 segments may be separated by CR (\r), LF (\n), or CRLF
        String[] lines = hl7.split("\r?\n|\r");
        for (String line : lines) {
            if (line.startsWith("MSH")) {
                String[] fields = line.split("\\|");
                if (fields.length > 2) d.sendingApp = fields[2];
                if (fields.length > 3) d.sendingFacility = fields[3];
                if (fields.length > 4) d.receivingApp = fields[4];
                if (fields.length > 5) d.receivingFacility = fields[5];
                if (fields.length > 6) d.messageDateTime = fields[6];
                if (fields.length > 8) d.eventCode = fields[8];
            } else if (line.startsWith("PV1")) {
                String[] fields = line.split("\\|"); // pipe
                if (fields.length > 3) {
                    d.location = fields[3];
                    String[] locComps = fields[3].split("\\^");
                    if (locComps.length > 0) d.locationPoc = locComps[0];
                    if (locComps.length > 1) d.locationRoom = locComps[1];
                    if (locComps.length > 2) d.locationBed = locComps[2];
                }
                if (fields.length > 4) d.admissionType = fields[4]; // PV1-4 Admission type
                if (fields.length > 19) d.visitNumber = fields[19]; // PV1-19 Visit number
                if (fields.length > 18) d.accountNumber = fields[18]; // PV1-18 patient account number
                if (fields.length > 7) d.attendingName = fields[7];
                if (fields.length > 9) d.consultingName = fields[9];
                // PV1-44 Admission date/time
                if (fields.length > 44) {
                    d.admitDateTime = fields[44];
                }
            } else if (line.startsWith("NK1")) {
                String[] fields = line.split("\\|");
                if (fields.length > 2) d.nk1Name = fields[2];
                if (fields.length > 3) d.nk1RelationshipCode = fields[3];
                // NK1-5 is phone number according to v2.x definition
                if (fields.length > 5) {
                    String phone = fields[5];
                    // XTN may contain subcomponents like ext etc; keep first component
                    d.nk1Phone = phone.split("\\^")[0];
                }
            } else if (line.startsWith("AL1")) {
                String[] fields = line.split("\\|");
                if (fields.length > 3) d.allergyCode = fields[3]; // AL1-3 Allergy code string
                if (fields.length > 5) d.allergyReaction = fields[5]; // AL1-5 Reaction
            } else if (line.startsWith("IN1")) {
                String[] fields = line.split("\\|");
                if (fields.length > 3) d.insurancePayerId = fields[3]; // IN1-3 payer id
                if (fields.length > 4) d.insurancePayerName = fields[4]; // IN1-4 payer name
                if (fields.length > 8) d.insuranceGroupNumber = fields[8]; // IN1-9 Group number
            } else if (line.startsWith("GT1")) {
                String[] fields = line.split("\\|");
                if (fields.length > 3) d.guarantorName = fields[3];
                if (fields.length > 5) d.guarantorPhone = fields[5];
            }
            else if (line.startsWith("PID")) {
                String[] fields = line.split("\\|");
                if (fields.length > 5) {
                    // PID-5 may contain multiple repetitions separated by ~ ; take first
                    String nameField = fields[5].split("~")[0];
                    d.patientName = nameField.trim();
                }
                if (fields.length > 7 && !fields[7].isBlank()) {
                    String dobField = fields[7].trim();
                    // keep first 8 chars YYYYMMDD
                    if (dobField.length() >= 8) dobField = dobField.substring(0, 8);
                    d.patientDob = dobField;
                }
                if (fields.length > 8) d.patientGender = fields[8].trim();
                if (fields.length > 12) d.patientPhone = fields[12]; // PID-13
                if (fields.length > 10) d.patientRace = fields[10]; // PID-10 race
                if (fields.length > 14) d.patientLanguage = fields[14]; // PID-15
                if (fields.length > 15) d.patientMaritalStatus = fields[15]; // PID-16
                if (fields.length > 16) d.patientReligion = fields[16]; // PID-17
            }
        }
        return d;
    }
} 