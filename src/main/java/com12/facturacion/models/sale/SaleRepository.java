package com12.facturacion.models.sale;

import com12.facturacion.models.table.Table;
import com12.facturacion.models.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Repository
public interface SaleRepository extends JpaRepository<Sale, Long> {
    List<Sale> findByUserAndDateBetween(User user, LocalDateTime inicio, LocalDateTime fin);
    List<Sale> findByDateBetween(LocalDateTime inicio, LocalDateTime fin);
    List<Sale> findByTable(Table table);

    List<Sale> findByDateBetweenAndStatus(LocalDateTime start, LocalDateTime end, StatusSale statusSale);
}
