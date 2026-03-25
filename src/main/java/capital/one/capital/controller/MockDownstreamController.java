package capital.one.capital.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dev-only stub that replaces the real downstream institution endpoints.
 * All calls return a positive (accepted / verified) response so the full
 * flow can be exercised locally without a live counterparty.
 *
 * Activated only when spring.profiles.active=dev.
 */
@RestController
@RequestMapping("/mock")
@Profile("dev")
public class MockDownstreamController {

    private static final Logger log = LoggerFactory.getLogger(MockDownstreamController.class);

    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    // ── helpers ───────────────────────────────────────────────────────────────

    private String now() {
        return OffsetDateTime.now().format(ISO_DT);
    }

    private String mockId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    /** Pulls the first occurrence of an XML element value out of the request body. */
    private String extract(String xml, String element) {
        Matcher m = Pattern.compile("<" + element + ">([^<]+)</" + element + ">").matcher(xml);
        return m.find() ? m.group(1) : "UNKNOWN";
    }

    // ── POST /mock/credit  →  pacs.002 ACCC ──────────────────────────────────

    @PostMapping(value = "/credit",
                 consumes = MediaType.APPLICATION_XML_VALUE,
                 produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> mockCredit(@RequestBody String requestXml) {
        String orgnlMsgId    = extract(requestXml, "MsgId");
        String orgnlE2eId    = extract(requestXml, "EndToEndId");
        log.info("[MOCK] credit received — orgnlMsgId={} e2eId={}", orgnlMsgId, orgnlE2eId);

        String response = pacs002Xml(orgnlMsgId, orgnlE2eId, "ACCC");
        log.info("[MOCK] pacs.002 response (credit):\n{}", response);
        return ResponseEntity.ok(response);
    }

    // ── POST /mock/status  →  pacs.002 ACCC ──────────────────────────────────

    @PostMapping(value = "/status",
                 consumes = MediaType.APPLICATION_XML_VALUE,
                 produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> mockStatus(@RequestBody String requestXml) {
        String orgnlMsgId = extract(requestXml, "OrgnlMsgId");
        log.info("[MOCK] status query received — orgnlMsgId={}", orgnlMsgId);

        String response = pacs002Xml(orgnlMsgId, "N/A", "ACCC");
        log.info("[MOCK] pacs.002 response (status):\n{}", response);
        return ResponseEntity.ok(response);
    }

    // ── POST /mock/return  →  pacs.002 ACCP ──────────────────────────────────

    @PostMapping(value = "/return",
                 consumes = MediaType.APPLICATION_XML_VALUE,
                 produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> mockReturn(@RequestBody String requestXml) {
        String orgnlMsgId = extract(requestXml, "OrgnlMsgId");
        log.info("[MOCK] return received — orgnlMsgId={}", orgnlMsgId);

        String response = pacs002Xml(orgnlMsgId, "N/A", "ACCP");
        log.info("[MOCK] pacs.002 response (return):\n{}", response);
        return ResponseEntity.ok(response);
    }

    // ── POST /mock/avs  →  acmt.024 verified=true ─────────────────────────────

    @PostMapping(value = "/avs",
                 consumes = MediaType.APPLICATION_XML_VALUE,
                 produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> mockAvs(@RequestBody String requestXml) {
        String orgnlRefId = extract(requestXml, "Id");   // Vrfctn/Id = referenceId
        log.info("[MOCK] AVS received — orgnlRefId={}", orgnlRefId);

        String response = acmt024Xml(orgnlRefId);
        log.info("[MOCK] acmt.024 response:\n{}", response);
        return ResponseEntity.ok(response);
    }

    // ── XML builders ──────────────────────────────────────────────────────────

    private String pacs002Xml(String orgnlMsgId, String orgnlE2eId, String status) {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.16">
                    <FIToFIPmtStsRpt>
                        <GrpHdr>
                            <MsgId>%s</MsgId>
                            <CreDtTm>%s</CreDtTm>
                        </GrpHdr>
                        <TxInfAndSts>
                            <OrgnlGrpInf>
                                <OrgnlMsgId>%s</OrgnlMsgId>
                                <OrgnlMsgNmId>pacs.008.001.14</OrgnlMsgNmId>
                            </OrgnlGrpInf>
                            <OrgnlEndToEndId>%s</OrgnlEndToEndId>
                            <TxSts>%s</TxSts>
                        </TxInfAndSts>
                    </FIToFIPmtStsRpt>
                </Document>
                """.formatted(mockId("MOCK-"), now(), orgnlMsgId, orgnlE2eId, status);
    }

    private String acmt024Xml(String orgnlRefId) {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:acmt.024.001.04">
                    <IdVrfctnRpt>
                        <Assgnmt>
                            <MsgId>%s</MsgId>
                            <CreDtTm>%s</CreDtTm>
                            <Assgnr>
                                <Agt><FinInstnId><BICFI>MOCKBICXXXXX</BICFI></FinInstnId></Agt>
                            </Assgnr>
                            <Assgne>
                                <Agt><FinInstnId><BICFI>CAPZAZJJXXX</BICFI></FinInstnId></Agt>
                            </Assgne>
                        </Assgnmt>
                        <Rpt>
                            <OrgnlId>%s</OrgnlId>
                            <Vrfctn>true</Vrfctn>
                        </Rpt>
                    </IdVrfctnRpt>
                </Document>
                """.formatted(mockId("MOCK-"), now(), orgnlRefId);
    }
}
