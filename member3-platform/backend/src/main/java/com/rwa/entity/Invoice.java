package com.rwa.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
@Data
@TableName("invoice")
public class Invoice {
 @TableId(type=IdType.AUTO) private Long id;
 private String invoiceCode;
 private String invoiceNumber;
 private Long coreEnterpriseId;
 private Long supplierId;
 private BigDecimal amount;
 private LocalDate issueDate;
 private LocalDate dueDate;
 private String status;
 private String fileHash;
 private LocalDateTime createdAt;
 private LocalDateTime updatedAt;
}
