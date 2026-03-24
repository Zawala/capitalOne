package capital.one.capital.repository;

import capital.one.capital.model.AvsLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AvsLogRepository extends JpaRepository<AvsLog, Long> {

    List<AvsLog> findByAccountNumber(String accountNumber);

    List<AvsLog> findByBankBic(String bankBic);
}
