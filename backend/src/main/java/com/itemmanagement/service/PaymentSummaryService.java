package com.itemmanagement.service;

import com.itemmanagement.entity.enums.PaymentMethod;
import com.itemmanagement.exception.ApiException;
import com.itemmanagement.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PaymentSummaryService {
    private final NamedParameterJdbcTemplate jdbc;

    public record MethodTotal(String method, long transactions, BigDecimal collected) {}
    public record Cashier(long id, String name) {}
    public record Summary(BigDecimal billAmount, BigDecimal collected, BigDecimal netReceived,
                          long bills, long paid, long partiallyPaid, long unpaid,
                          List<MethodTotal> methods, List<Cashier> cashiers) {}

    @Transactional(readOnly = true)
    public Summary summarize(LocalDate from, LocalDate to, Long storeId, Long branchId,
                             Long cashierId, PaymentMethod method) {
        long tenant = TenantContext.storeId();
        if (storeId != null && storeId != tenant) {
            throw ApiException.forbidden("The selected store is outside your access");
        }
        if (branchId != null) {
            TenantContext.requireBranch(branchId);
        }
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.withDayOfMonth(1) : from;
        if (start.isAfter(end)) {
            throw ApiException.badRequest("From date cannot be after to date");
        }

        var user = TenantContext.user();
        var params = new MapSqlParameterSource()
                .addValue("store", tenant).addValue("branch", branchId, Types.BIGINT)
                .addValue("cashier", cashierId, Types.BIGINT)
                .addValue("method", method == null ? null : method.name(), Types.VARCHAR)
                .addValue("from", start).addValue("to", end)
                .addValue("allBranches", user.allBranches())
                .addValue("branches", user.branchIds().isEmpty() ? List.of(-1L) : user.branchIds());

        String scope = """
                FROM sales s
                WHERE s.store_id = :store AND s.status = 'COMPLETED'
                  AND s.sales_date BETWEEN :from AND :to
                  AND (:branch IS NULL OR s.branch_id = :branch)
                  AND (:allBranches OR s.branch_id IN (:branches))
                """;
        List<Cashier> cashiers = jdbc.query("""
                SELECT DISTINCT u.id, u.full_name
                FROM users u JOIN (SELECT s.created_by
                """ + scope + """
                ) billed ON billed.created_by = u.id
                WHERE u.store_id = :store ORDER BY u.full_name, u.id
                """, params, (rs, row) -> new Cashier(rs.getLong(1), rs.getString(2)));

        return jdbc.query("""
                WITH scoped AS (
                    SELECT s.id, s.grand_total, s.paid_amount, s.payment_method, s.payments
                """ + scope + """
                    AND (:cashier IS NULL OR s.created_by = :cashier)
                ), tender AS (
                    SELECT s.id, p.method, p.amount
                    FROM scoped s CROSS JOIN LATERAL
                        jsonb_to_recordset(COALESCE(s.payments,
                            jsonb_build_array(jsonb_build_object('method', s.payment_method, 'amount', s.paid_amount))))
                        AS p(method text, amount numeric)
                    WHERE p.amount > 0 AND (:method IS NULL OR p.method = :method)
                ), bill_totals AS (
                    SELECT COALESCE(SUM(grand_total), 0) AS bill_amount, COUNT(*) AS bills,
                           COUNT(*) FILTER (WHERE paid_amount >= grand_total) AS paid,
                           COUNT(*) FILTER (WHERE paid_amount > 0 AND paid_amount < grand_total) AS partial,
                           COUNT(*) FILTER (WHERE paid_amount = 0 AND grand_total > 0) AS unpaid
                    FROM scoped s
                    WHERE :method IS NULL OR EXISTS (SELECT 1 FROM tender t WHERE t.id = s.id)
                ), method_totals AS (
                    SELECT method, COUNT(*) AS transactions, SUM(amount) AS collected
                    FROM tender GROUP BY method
                )
                SELECT b.*, m.method, m.transactions, m.collected
                FROM bill_totals b LEFT JOIN method_totals m ON true ORDER BY m.method
                """, params, rs -> {
            rs.next();
            BigDecimal billAmount = rs.getBigDecimal("bill_amount");
            long bills = rs.getLong("bills");
            long paid = rs.getLong("paid");
            long partial = rs.getLong("partial");
            long unpaid = rs.getLong("unpaid");
            BigDecimal collected = BigDecimal.ZERO;
            List<MethodTotal> methods = new ArrayList<>();
            do {
                if (rs.getString("method") != null) {
                    BigDecimal amount = rs.getBigDecimal("collected");
                    methods.add(new MethodTotal(rs.getString("method"), rs.getLong("transactions"), amount));
                    collected = collected.add(amount);
                }
            } while (rs.next());
            return new Summary(billAmount, collected, collected, bills, paid, partial, unpaid, methods, cashiers);
        });
    }
}
