package cl.bancoxyz.account;
import jakarta.validation.Valid;import jakarta.validation.constraints.*;import java.math.BigDecimal;import java.util.*;import org.springframework.http.*;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/accounts") public class AccountController {
 private final AccountStore store; public AccountController(AccountStore store){this.store=store;}
 @GetMapping public List<Account> all(){return store.all();}
 @GetMapping("/{id}") public ResponseEntity<Account> get(@PathVariable long id){return store.get(id).map(ResponseEntity::ok).orElseGet(()->ResponseEntity.notFound().build());}
 @PostMapping public ResponseEntity<Account> open(@Valid @RequestBody OpenAccountRequest r){return ResponseEntity.status(HttpStatus.CREATED).body(store.open(r.customerId(),r.type(),r.initialBalance()));}
 @PatchMapping("/{id}/close") public ResponseEntity<Account> close(@PathVariable long id){return store.close(id).map(ResponseEntity::ok).orElseGet(()->ResponseEntity.notFound().build());}
 @PostMapping("/{id}/adjust") public ResponseEntity<Account> adjust(@PathVariable long id,@Valid @RequestBody AdjustmentRequest r){return store.adjust(id,r.amount()).map(ResponseEntity::ok).orElseGet(()->ResponseEntity.badRequest().build());}
 public record OpenAccountRequest(@NotNull Long customerId,@NotBlank String type,@NotNull @PositiveOrZero BigDecimal initialBalance){}
 public record AdjustmentRequest(@NotNull BigDecimal amount){}
}
