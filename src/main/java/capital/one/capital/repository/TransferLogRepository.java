package capital.one.capital.repository;

import capital.one.capital.model.TransferLog;
import capital.one.capital.model.TransferStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TransferLogRepository extends JpaRepository<TransferLog, Long> {

    Optional<TransferLog> findByMessageId(String messageId);

    List<TransferLog> findByStatus(TransferStatus status);

    List<TransferLog> findByDebtorAccount(String debtorAccount);
}
