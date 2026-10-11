package cl.bancoxyz.account;
import java.math.BigDecimal;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicLong;import org.springframework.stereotype.Service;
@Service public class AccountStore {
 private final Map<Long,Account> data=new ConcurrentHashMap<>(); private final AtomicLong ids=new AtomicLong(200);
 public AccountStore(){data.put(101L,new Account(101L,1L,"AHORRO",new BigDecimal("1250000"),"ACTIVE"));data.put(102L,new Account(102L,2L,"CORRIENTE",new BigDecimal("840000"),"ACTIVE"));data.put(103L,new Account(103L,1L,"AHORRO",new BigDecimal("560000"),"ACTIVE"));}
 public List<Account> all(){return data.values().stream().sorted(Comparator.comparing(Account::id)).toList();}
 public Optional<Account> get(long id){return Optional.ofNullable(data.get(id));}
 public Account open(Long customerId,String type,BigDecimal initial){long id=ids.incrementAndGet();Account a=new Account(id,customerId,type.toUpperCase(Locale.ROOT),initial,"ACTIVE");data.put(id,a);return a;}
 public Optional<Account> close(long id){Account a=data.get(id); if(a==null)return Optional.empty(); Account c=new Account(a.id(),a.customerId(),a.type(),a.balance(),"CLOSED");data.put(id,c);return Optional.of(c);}
 public synchronized Optional<Account> adjust(long id,BigDecimal delta){Account a=data.get(id); if(a==null||!"ACTIVE".equals(a.status()))return Optional.empty();BigDecimal n=a.balance().add(delta);if(n.signum()<0)return Optional.empty();Account u=new Account(a.id(),a.customerId(),a.type(),n,a.status());data.put(id,u);return Optional.of(u);}
}
