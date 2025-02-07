package com12.facturacion.models.sale;

import com12.facturacion.infra.erros.ResourceNotFoundException;
import com12.facturacion.models.product.ProductRepository;
import com12.facturacion.models.product.Product;
import com12.facturacion.models.saledetail.SaleDetail;
import com12.facturacion.models.saledetail.SaleDetailDTO;
import com12.facturacion.models.saledetail.SaleDetailRepository;
import com12.facturacion.models.table.StatusTable;
import com12.facturacion.models.table.Table;
import com12.facturacion.models.table.TableRepository;
import com12.facturacion.models.user.User;
import com12.facturacion.models.user.UserRepository;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SaleService {
    private final SaleRepository ventaRepository;
    private final TableRepository mesaRepository;
    private final ProductRepository productoRepository;
    private final UserRepository usuarioRepository;
    private final SaleDetailRepository detalleVentaRepository;

    @Autowired
    public SaleService(SaleRepository ventaRepository, TableRepository mesaRepository, ProductRepository productoRepository, UserRepository usuarioRepository, SaleDetailRepository detalleVentaRepository) {
        this.ventaRepository = ventaRepository;
        this.mesaRepository = mesaRepository;
        this.productoRepository = productoRepository;
        this.usuarioRepository = usuarioRepository;
        this.detalleVentaRepository = detalleVentaRepository;
    }

    @Transactional
    public SaleDTO crearVenta(SaleRequest request) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = usuarioRepository.findUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));

        Table table = mesaRepository.findById(request.tableId())
                .orElseThrow(() -> new ResourceNotFoundException("Mesa no encontrada"));

        if (request.detail() == null || request.detail().isEmpty()) {
            throw new IllegalArgumentException("Los detalles de la venta no pueden estar vacíos");
        }

        if (table.getEstado() != StatusTable.LIBRE) {
            throw new IllegalStateException("La mesa no está disponible");
        }

        Sale sale = new Sale();
        sale.setUser(user);
        sale.setTable(table);
        sale.setDate(LocalDateTime.now());
        sale.setStatus(StatusSale.PENDIENTE);

        Sale finalSale = sale;
        List<SaleDetail> detalles = request.detail().stream()
                .map(detalleDTO -> crearDetalleVenta(detalleDTO, finalSale))
                .collect(Collectors.toList());

        BigDecimal totalVenta = calcularTotal(detalles);

        sale.setTotal(totalVenta);
        sale = ventaRepository.save(sale);

        detalleVentaRepository.saveAll(detalles);
        table.setEstado(StatusTable.OCUPADA);
        mesaRepository.save(table);

        return convertToDTO(sale);
    }


    @Transactional
    public SaleDTO completarVenta(Long ventaId) {
        Sale venta = ventaRepository.findById(ventaId)
                .orElseThrow(() -> new ResourceNotFoundException("Venta no encontrada"));

        if (venta.getStatus() != StatusSale.PENDIENTE) {
            throw new IllegalStateException("La venta no está en estado pendiente");
        }

        venta.setStatus(StatusSale.COMPLETADA);

        Table mesa = venta.getTable();
        mesa.setEstado(StatusTable.LIBRE);
        mesaRepository.save(mesa);

        venta.getDetails().forEach(this::actualizarStock);

        return convertToDTO(ventaRepository.save(venta));
    }

    private void actualizarStock(SaleDetail detalle) {
        Product product = detalle.getProduct();
        int nuevoStock = product.getStock() - detalle.getCantidad();
        if (nuevoStock < 0) {
            throw new IllegalStateException("Stock insuficiente para el producto: " + product.getName());
        }
        product.setStock(nuevoStock);
        productoRepository.save(product);
    }

    private SaleDetail crearDetalleVenta(SaleDetailDTO detalle, Sale venta) {
        Product product = productoRepository.findById(detalle.productoId())
                .orElseThrow(() -> new ResourceNotFoundException("Producto no encontrado"));

        SaleDetail detalleVenta = new SaleDetail();
        detalleVenta.setSale(venta);
        detalleVenta.setProduct(product);
        detalleVenta.setCantidad(detalle.cantidad());
        detalleVenta.setPrecioUnitario(product.getPrice());
        detalleVenta.setSubtotal(product.getPrice().multiply(BigDecimal.valueOf(detalle.cantidad())));

        return detalleVenta;
    }

    private BigDecimal calcularTotal(List<SaleDetail> detalles) {
        return detalles.stream()
                .map(SaleDetail::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private SaleDTO convertToDTO(Sale venta) {
        List<SaleDetailDTO> detallesDTO = venta.getDetails().stream()
                .map(this::convertDetalleToDTO)
                .collect(Collectors.toList());

        return new SaleDTO(
                venta.getId(),
                venta.getUser().getId(),
                venta.getTable().getId(),
                venta.getDate(),
                venta.getTotal(),
                venta.getStatus(),
                detallesDTO
        );
    }

    private SaleDetailDTO convertDetalleToDTO(SaleDetail detalle) {
        return new SaleDetailDTO(
                detalle.getProduct() != null ? detalle.getProduct().getId() : null,
                detalle.getCantidad(),
                detalle.getPrecioUnitario(),
                detalle.getSubtotal()
        );
    }


    public List<SaleDTO> obtenerVentasPorFecha(LocalDateTime inicio, LocalDateTime fin) {
        return ventaRepository.findByDateBetween(inicio, fin)
                .stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    public List<SaleDTO> obtenerVentasUsuario(LocalDateTime inicio, LocalDateTime fin) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User usuario = usuarioRepository.findUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));

        return ventaRepository.findByUserAndDateBetween(usuario, inicio, fin)
                .stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public SaleDTO agregarProductos(Long saleId, AgregarProductoDTO request) {
        // Buscar la venta existente
        Sale sale = ventaRepository.findById(saleId)
                .orElseThrow(() -> new ResourceNotFoundException("Venta no encontrada"));

        // Para cada nuevo detalle en la petición, se crea un SaleDetail
        Sale finalSale = sale;
        List<SaleDetail> nuevosDetalles = request.detail().stream()
                .map(nuevoDetalle -> {
                    Product product = productoRepository.findById(nuevoDetalle.productoId())
                            .orElseThrow(() -> new ResourceNotFoundException("Producto no encontrado"));
                    SaleDetail detalle = new SaleDetail();
                    detalle.setSale(finalSale);
                    detalle.setProduct(product);
                    detalle.setCantidad(nuevoDetalle.cantidad());
                    detalle.setPrecioUnitario(product.getPrice());
                    detalle.setSubtotal(
                            BigDecimal.valueOf(nuevoDetalle.cantidad()).multiply(product.getPrice())
                    );
                    return detalle;
                })
                .collect(Collectors.toList());

        sale.getDetails().addAll(nuevosDetalles);

        detalleVentaRepository.saveAll(nuevosDetalles);

        BigDecimal nuevoTotal = sale.getDetails().stream()
                .map(SaleDetail::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        sale.setTotal(nuevoTotal);

        sale = ventaRepository.save(sale);

        return convertToDTO(sale);
    }

    @Transactional
    public SaleDTO eliminarProductoDeVenta(Long saleId, Long productoId) {
        Sale sale = ventaRepository.findById(saleId)
                .orElseThrow(() -> new ResourceNotFoundException("Venta no encontrada"));

        SaleDetail detalleAEliminar = sale.getDetails().stream()
                .filter(detail -> detail.getProduct().getId().equals(productoId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Producto no encontrado en la venta"));

        sale.getDetails().remove(detalleAEliminar);

        BigDecimal nuevoTotal = sale.getDetails().stream()
                .map(SaleDetail::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        sale.setTotal(nuevoTotal);

        sale = ventaRepository.save(sale);

        return convertToDTO(sale);
    }
}