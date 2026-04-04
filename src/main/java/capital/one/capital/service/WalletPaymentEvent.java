package capital.one.capital.service;

import java.math.BigDecimal;

/**
 * Flat key-value payload published to Kafka for both deposit and credit events.
 *
 * <pre>
 * Field                Source (pacs.008)
 * ──────────────────────────────────────────────────────────
 * messageId             GrpHdr/MsgId
 * senderInstitution     GrpHdr/InstgAgt/FinInstnId/Othr/Id  (or BIC)
 * senderName            Dbtr/Nm
 * senderAccount         DbtrAcct or Dbtr/CtctDtls/MobNb
 * amount                IntrBkSttlmAmt
 * currency              IntrBkSttlmAmt/@Ccy
 * receiverInstitution   GrpHdr/InstdAgt/FinInstnId/Othr/Id  (or BIC)
 * receiverName          Cdtr/Nm
 * receiverAccount       CdtrAcct or Cdtr/CtctDtls/MobNb
 * </pre>
 */
public record WalletPaymentEvent(
        String messageId,
        String senderInstitution,
        String senderName,
        String senderAccount,
        BigDecimal amount,
        String currency,
        String receiverInstitution,
        String receiverName,
        String receiverAccount
) {}
