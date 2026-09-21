package com.rwa.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
@Data
@TableName("loan_application")
public class LoanApplication {
 @TableId(type=IdType.AUTO) private Long id;
 private String applicationNo;
 private String tokenId;
 private Long supplierId;
 private Long bankId;
 private BigDecimal amount;
 private BigDecimal interestRate;
 private Integer termDays;
 private String status;
 private String chainTxHash;
 private String remark;
 private LocalDateTime createdAt;
 private LocalDateTime updatedAt;
}
