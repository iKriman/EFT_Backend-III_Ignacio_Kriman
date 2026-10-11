package cl.bancoxyz.account;
import java.math.BigDecimal;
public record Account(Long id, Long customerId, String type, BigDecimal balance, String status) {}
