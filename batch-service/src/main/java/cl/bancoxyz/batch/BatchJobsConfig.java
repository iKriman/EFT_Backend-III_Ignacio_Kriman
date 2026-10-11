package cl.bancoxyz.batch;

import cl.bancoxyz.batch.model.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.FlatFileItemWriter;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.builder.FlatFileItemWriterBuilder;
import org.springframework.batch.item.support.SynchronizedItemStreamReader;
import org.springframework.batch.item.support.SynchronizedItemStreamWriter;
import org.springframework.batch.item.support.builder.SynchronizedItemStreamReaderBuilder;
import org.springframework.batch.item.support.builder.SynchronizedItemStreamWriterBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class BatchJobsConfig {

    private LocalDate date(String raw) {
        for (DateTimeFormatter formatter : List.of(
                DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("dd-MM-uuuu"),
                DateTimeFormatter.ofPattern("dd/MM/uuuu"),
                DateTimeFormatter.ofPattern("uuuu/MM/dd"))) {
            try {
                return LocalDate.parse(raw.trim(), formatter);
            } catch (Exception ignored) {
            }
        }
        throw new IllegalArgumentException("Fecha invalida: " + raw);
    }

    @Bean
    SynchronizedItemStreamReader<DailyTransaction> transactionReader() {
        FlatFileItemReader<DailyTransaction> delegate =
                new FlatFileItemReaderBuilder<DailyTransaction>()
                        .name("transactionReaderDelegate")
                        .resource(new ClassPathResource("legacy/movimientos_financieros_diarios.csv"))
                        .linesToSkip(1)
                        .delimited()
                        .names("id", "fecha", "monto", "tipo")
                        .fieldSetMapper(fs -> {
                            BigDecimal amount = fs.readBigDecimal("monto");
                            String type = fs.readString("tipo").trim().toLowerCase();

                            if (amount == null
                                    || amount.signum() <= 0
                                    || !(type.equals("credito") || type.equals("debito"))) {
                                throw new IllegalArgumentException("Transaccion invalida");
                            }

                            return new DailyTransaction(
                                    fs.readLong("id"),
                                    date(fs.readString("fecha")),
                                    amount,
                                    type);
                        })
                        .build();

        return new SynchronizedItemStreamReaderBuilder<DailyTransaction>()
                .delegate(delegate)
                .build();
    }

    @Bean
    SynchronizedItemStreamWriter<DailyTransaction> transactionWriter() {
        FlatFileItemWriter<DailyTransaction> delegate =
                new FlatFileItemWriterBuilder<DailyTransaction>()
                        .name("transactionWriterDelegate")
                        .resource(new FileSystemResource("output/reporte_transacciones_diarias.csv"))
                        .shouldDeleteIfExists(true)
                        .headerCallback(writer -> writer.write("id,fecha,monto,tipo"))
                        .lineAggregator(item -> item.id() + "," + item.date() + "," + item.amount() + "," + item.type())
                        .build();

        return new SynchronizedItemStreamWriterBuilder<DailyTransaction>()
                .delegate(delegate)
                .build();
    }

    @Bean
    Step transactionStep(JobRepository repo, PlatformTransactionManager tx) {
        return new StepBuilder("transactionStep", repo)
                .<DailyTransaction, DailyTransaction>chunk(50, tx)
                .reader(transactionReader())
                .processor(item -> item)
                .writer(transactionWriter())
                .faultTolerant()
                .skip(FlatFileParseException.class)
                .skip(IllegalArgumentException.class)
                .skipLimit(1000)
                .retry(ItemStreamException.class)
                .retry(org.springframework.dao.TransientDataAccessException.class)
                .retryLimit(3)
                .build();
    }

    @Bean
    Job dailyTransactionsJob(JobRepository repo, Step transactionStep) {
        return new JobBuilder("dailyTransactionsJob", repo)
                .start(transactionStep)
                .build();
    }

    @Bean
    FlatFileItemReader<InterestInput> interestReader() {
        return new FlatFileItemReaderBuilder<InterestInput>()
                .name("interestReader")
                .resource(new ClassPathResource("legacy/intereses_trimestrales.csv"))
                .linesToSkip(1)
                .delimited()
                .names("cuenta_id", "nombre", "saldo", "edad", "tipo")
                .fieldSetMapper(fs -> {
                    BigDecimal balance = fs.readBigDecimal("saldo");
                    int age = fs.readInt("edad");
                    String type = fs.readString("tipo").trim().toLowerCase();

                    if (balance == null
                            || balance.signum() < 0
                            || age < 18
                            || age > 100
                            || !(type.equals("ahorro")
                            || type.equals("prestamo")
                            || type.equals("hipoteca"))) {
                        throw new IllegalArgumentException("Cuenta invalida");
                    }

                    return new InterestInput(
                            fs.readLong("cuenta_id"),
                            fs.readString("nombre"),
                            balance,
                            age,
                            type);
                })
                .build();
    }

    @Bean
    ItemProcessor<InterestInput, InterestResult> interestProcessor() {
        return input -> {
            BigDecimal annual = input.type().equals("ahorro")
                    ? new BigDecimal("0.036")
                    : new BigDecimal("0.084");

            BigDecimal monthly = input.balance()
                    .multiply(annual)
                    .divide(new BigDecimal("12"), 2, RoundingMode.HALF_UP);

            return new InterestResult(
                    input.accountId(),
                    input.name(),
                    input.balance(),
                    monthly,
                    input.balance().add(monthly),
                    input.type());
        };
    }

    @Bean
    FlatFileItemWriter<InterestResult> interestWriter() {
        return new FlatFileItemWriterBuilder<InterestResult>()
                .name("interestWriter")
                .resource(new FileSystemResource("output/calculo_intereses_mensuales.csv"))
                .shouldDeleteIfExists(true)
                .headerCallback(writer -> writer.write(
                        "cuenta_id,nombre,saldo_original,interes_mensual,saldo_final,tipo"))
                .lineAggregator(item -> item.accountId()
                        + "," + item.name()
                        + "," + item.originalBalance()
                        + "," + item.monthlyInterest()
                        + "," + item.finalBalance()
                        + "," + item.type())
                .build();
    }

    @Bean
    Step interestStep(JobRepository repo, PlatformTransactionManager tx) {
        return new StepBuilder("interestStep", repo)
                .<InterestInput, InterestResult>chunk(50, tx)
                .reader(interestReader())
                .processor(interestProcessor())
                .writer(interestWriter())
                .faultTolerant()
                .skip(FlatFileParseException.class)
                .skip(IllegalArgumentException.class)
                .skipLimit(1000)
                .retry(ItemStreamException.class)
                .retry(org.springframework.dao.TransientDataAccessException.class)
                .retryLimit(3)
                .build();
    }

    @Bean
    Job monthlyInterestJob(JobRepository repo, Step interestStep) {
        return new JobBuilder("monthlyInterestJob", repo)
                .start(interestStep)
                .build();
    }

    @Bean
    FlatFileItemReader<AnnualMovement> annualReader() {
        return new FlatFileItemReaderBuilder<AnnualMovement>()
                .name("annualReader")
                .resource(new ClassPathResource("legacy/estados_financieros_anuales.csv"))
                .linesToSkip(1)
                .delimited()
                .names("cuenta_id", "fecha", "transaccion", "monto", "descripcion")
                .fieldSetMapper(fs -> {
                    BigDecimal amount = fs.readBigDecimal("monto");
                    String description = fs.readString("descripcion");

                    if (amount == null
                            || amount.signum() <= 0
                            || description == null
                            || description.isBlank()) {
                        throw new IllegalArgumentException("Movimiento anual invalido");
                    }

                    return new AnnualMovement(
                            fs.readLong("cuenta_id"),
                            date(fs.readString("fecha")),
                            fs.readString("transaccion"),
                            amount,
                            description.trim());
                })
                .build();
    }

    @Bean
    FlatFileItemWriter<AnnualMovement> annualWriter() {
        return new FlatFileItemWriterBuilder<AnnualMovement>()
                .name("annualWriter")
                .resource(new FileSystemResource("output/estados_cuenta_anuales.csv"))
                .shouldDeleteIfExists(true)
                .headerCallback(writer -> writer.write("cuenta_id,fecha,tipo,monto,descripcion"))
                .lineAggregator(item -> item.accountId()
                        + "," + item.date()
                        + "," + item.type()
                        + "," + item.amount()
                        + "," + item.description().replace(",", " "))
                .build();
    }

    @Bean
    Step annualStep(JobRepository repo, PlatformTransactionManager tx) {
        return new StepBuilder("annualStep", repo)
                .<AnnualMovement, AnnualMovement>chunk(100, tx)
                .reader(annualReader())
                .processor(item -> item)
                .writer(annualWriter())
                .faultTolerant()
                .skip(FlatFileParseException.class)
                .skip(IllegalArgumentException.class)
                .skipLimit(1000)
                .retry(ItemStreamException.class)
                .retry(org.springframework.dao.TransientDataAccessException.class)
                .retryLimit(3)
                .build();
    }

    @Bean
    Job annualStatementsJob(JobRepository repo, Step annualStep) {
        return new JobBuilder("annualStatementsJob", repo)
                .start(annualStep)
                .build();
    }
}
