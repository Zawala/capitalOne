package capital.one.capital.repository;

import capital.one.capital.model.TransferLog;
import capital.one.capital.model.TransferStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransferLogRepository extends JpaRepository<TransferLog, Long> {

    List<TransferLog> findByStatus(TransferStatus status);

    List<TransferLog> findByDebtorAccount(String debtorAccount);
}
