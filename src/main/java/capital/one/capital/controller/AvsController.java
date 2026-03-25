package capital.one.capital.controller;

import capital.one.capital.config.InstitutionProperties;
import capital.one.capital.dto.AvsRequestDTO;
import capital.one.capital.dto.AvsResponseDTO;
import capital.one.capital.model.AvsLog;
import capital.one.capital.model.TransferStatus;
import capital.one.capital.repository.AvsLogRepository;
import capital.one.capital.service.Acmt023BuilderService;
import capital.one.capital.service.Acmt023BuilderService.BuildResult;
import capital.one.capital.service.Iso20022MarshallingService;

import jakarta.xml.bind.JAXBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/api/v1/avs")
public class AvsController {

    private static final Logger log = LoggerFactory.getLogger(AvsController.class);

    private final Acmt023BuilderService acmt023Builder;
    private final Iso20022MarshallingService marshaller;
    private final AvsLogRepository avsLogRepository;
    private final InstitutionProperties institution;
    private final RestTemplate restTemplate;

    public AvsController(
            Acmt023BuilderService acmt023Builder,
            Iso20022MarshallingService marshaller,
            AvsLogRepository avsLogRepository,
            InstitutionProperties institution,
            RestTemplate restTemplate) {
        this.acmt023Builder = acmt023Builder;
        this.marshaller = marshaller;
        this.avsLogRepository = avsLogRepository;
        this.institution = institution;
        this.restTemplate = restTemplate;
    }

    /**
     * Verifies that an account holder name matches an account number at the given bank.
     * <ol>
     *   <li>Builds an acmt.023 IdentificationVerificationRequest.</li>
     *   <li>Persists a PENDING log entry.</li>
     *   <li>Marshals to XML and POSTs to the AVS endpoint.</li>
     *   <li>Unmarshals the acmt.024 response and parses the verification result.</li>
     *   <li>Updates the log to SENT or FAILED.</li>
     * </ol>
     */
    @PostMapping("/verify")
    public ResponseEntity<AvsResponseDTO> verify(@RequestBody AvsRequestDTO request) {

        // ── 1. Build acmt.023 ─────────────────────────────────────────────────
        BuildResult built;
        String xml;
        try {
            built = acmt023Builder.build(request);
            xml   = marshaller.marshalAvsRequest(built.document());
            log.info("acmt.023 XML:\n{}", xml);
        } catch (JAXBException e) {
            log.error("Failed to build/marshal acmt.023", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        // ── 2. Persist PENDING log ────────────────────────────────────────────
        AvsLog avsLog = new AvsLog();
        avsLog.setMessageId(built.messageId());
        avsLog.setReferenceId(built.referenceId());
        avsLog.setAccountHolderName(request.getFirstName() + " " + request.getLastName());
        avsLog.setAccountNumber(request.getAccountNumber());
        avsLog.setBankBic(request.getBankBic());
        avsLog.setStatus(TransferStatus.PENDING);
        avsLog.setRawXmlSent(xml);
        avsLogRepository.save(avsLog);

        log.info("AVS PENDING — msgId={} refId={} account={} bank={}",
                built.messageId(), built.referenceId(),
                request.getAccountNumber(), request.getBankBic());

        // ── 3. POST to AVS endpoint ───────────────────────────────────────────
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        HttpEntity<String> httpRequest = new HttpEntity<>(xml, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    institution.getAvsUrl(), httpRequest, String.class);

            String responseXml = response.getBody();

            // ── 4. Unmarshal acmt.024 and parse result ────────────────────────
            capital.one.capital.iso.acmt.acmt024_001_04.Document responseDoc =
                    marshaller.unmarshalAvsResponse(responseXml);

            AvsResponseDTO result = acmt023Builder.parseResponse(responseDoc);

            // ── 5. Update log ─────────────────────────────────────────────────
            avsLog.setVerified(result.isVerified());
            avsLog.setReasonCode(result.getReasonCode());
            avsLog.setStatus(TransferStatus.SENT);
            avsLog.setRawXmlReceived(responseXml);
            avsLogRepository.save(avsLog);

            log.info("AVS COMPLETED — msgId={} verified={} reason={}",
                    built.messageId(), result.isVerified(), result.getReasonCode());

            return ResponseEntity.ok(result);

        } catch (RestClientException | JAXBException e) {
            avsLog.setStatus(TransferStatus.FAILED);
            avsLog.setRawXmlReceived(e.getMessage());
            avsLogRepository.save(avsLog);

            log.error("AVS FAILED — msgId={} error={}", built.messageId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }
}
